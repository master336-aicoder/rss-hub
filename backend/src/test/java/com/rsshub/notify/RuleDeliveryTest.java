package com.rsshub.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.rsshub.common.ApiException;
import com.rsshub.common.JdbcTimes;
import com.rsshub.feedfetch.TaskQueue;
import com.rsshub.feedfetch.TaskQueue.Task;
import com.rsshub.feedstore.EntryNewEvent;
import com.rsshub.rules.NotificationRepository;
import com.rsshub.rules.RuleRepository;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 03 §8.1's two halves, joined: a hit writes a fact and queues a send, and the send happens later, in
 * {@link DeliveryService}, against a receiver on loopback.
 *
 * <p>Both dispatchers are switched off. The notifier is the subject here, so the rounds are driven by hand —
 * {@link TaskQueue#claim} then {@link DeliveryService#run} — which is also the only way to assert on the backoff:
 * a background worker would claim the row before the test could look at {@code fire_at}. Where the ladder says
 * "wait five minutes", the test pulls that row due instead; the wait itself is what is under test, not the clock.
 *
 * <p>{@code notification_log.entry_id} is a real foreign key, so the published events carry ids of rows that exist.
 * Rules are written through {@link RuleRepository} rather than the API: this class is about what happens once a rule
 * has been saved, and {@code RulesApiTest} covers the saving.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:notify;DB_CLOSE_DELAY=-1",
    "rsshub.operator.username=admin",
    "rsshub.operator.password=a-test-only-password",
    "rsshub.fetch.enabled=false",
    "rsshub.notify.enabled=false",
    "rsshub.ssrf.allow-private-prefixes[0]=127.0.0.1",
    "rsshub.notify.secrets[test-hook]=a-test-signing-secret"
})
class RuleDeliveryTest {

    private static final String SECRET_REF = "test-hook";
    private static final String SECRET = "a-test-signing-secret";
    private static final Duration LADDER_TOLERANCE = Duration.ofSeconds(5);

    @Autowired
    TaskQueue queue;

    @Autowired
    DeliveryService delivery;

    @Autowired
    RuleRepository rules;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    JdbcClient db;

    private final ObjectMapper json = new ObjectMapper();

    private WebhookSink sink;
    private long feedId;
    private final AtomicInteger sequence = new AtomicInteger();

    @BeforeEach
    void reset() throws IOException {
        sink = new WebhookSink();
        db.sql("delete from notification_log").update();
        db.sql("delete from scheduled_task").update();
        db.sql("delete from rule").update();
        // entry cascades from feed, and a feed row needs nothing but its two URLs.
        db.sql("delete from feed").update();
        db.sql("insert into feed (canonical_url, requested_url, title, group_name) values (?, ?, ?, ?)")
            .params("http://example.test/feed", "http://example.test/feed", "Loopback Weekly", "生产")
            .update();
        feedId = db.sql("select id from feed").query(Long.class).single();
    }

    @AfterEach
    void closeSink() {
        sink.close();
    }

    @Test
    @DisplayName("FR-5.1/5.2: a hit records the fact and queues the send, and no socket is opened while deciding")
    void aHitRecordsTheFactAndQueuesTheSendWithoutOpeningAConnection() {
        long ruleId = rule(match("OOM"), action("webhook", sink.url("/hook"), SECRET_REF));

        publish("OOM on node-3");

        assertThat(sink.requests()).isEmpty();
        long notificationId = notification(1);
        assertThat(row(notificationId).state()).isEqualTo(NotificationRepository.PENDING);
        assertThat(row(notificationId).dedupeKey()).isEqualTo("rule:" + ruleId + ":perFeed:feed:" + feedId);
        assertThat(hits(ruleId)).isEqualTo(1);
        assertThat(taskState(notificationId)).isEqualTo("pending");
    }

    @Test
    @DisplayName("FR-5.2: the send carries 03 §8.3's nine fields and the terminal state lands in both tables")
    void deliveryPostsTheFrozenEnvelopeAndMarksTheRowSent() {
        rule(match("OOM"), action("webhook", sink.url("/hook"), null));
        publish("OOM at midnight");

        delivery.run(claim());

        WebhookSink.Received received = sink.last();
        JsonNode body = json.readTree(received.body());
        assertThat(body.propertyNames()).containsExactly("ruleId", "ruleName", "entryId", "title", "url", "summary",
            "feedTitle", "matchedKeywords", "publishedAt");
        assertThat(body.path("title").asString()).isEqualTo("OOM at midnight");
        assertThat(body.path("url").asString()).isEqualTo("http://example.test/oom-1");
        assertThat(body.path("feedTitle").asString()).isEqualTo("Loopback Weekly");
        assertThat(body.path("matchedKeywords")).hasSize(1);
        assertThat(received.first("user-agent")).isEqualTo("rss-hub/1.0 (alert webhook)");
        assertThat(received.first("content-type")).contains("application/json");

        long notificationId = notification(1);
        assertThat(row(notificationId).state()).isEqualTo(NotificationRepository.SENT);
        assertThat(row(notificationId).httpStatus()).isEqualTo(200);
        assertThat(row(notificationId).error()).isNull();
        assertThat(taskState(notificationId)).isEqualTo("success");
    }

    @Test
    @DisplayName("FR-5.2: editing the rule after the hit cannot change what the queued alert says")
    void theEnvelopeIsFrozenAtHitTimeSoALaterRuleEditDoesNotChangeTheSend() {
        long ruleId = rule(match("OOM"), action("webhook", sink.url("/first"), null));
        publish("OOM at midnight");
        String nameAtHitTime = ruleName(ruleId);

        // The operator repoints the rule before the queue gets to the hit; the queued alert keeps the old target.
        rules.update(ruleId, "renamed", match("panic"), action("webhook", sink.url("/second"), null), null, null);
        delivery.run(claim());

        assertThat(sink.last().path()).isEqualTo("/first");
        assertThat(sink.requests()).hasSize(1);
        JsonNode body = json.readTree(sink.last().body());
        assertThat(body.path("ruleName").asString()).isEqualTo(nameAtHitTime);
        assertThat(row(notification(1)).state()).isEqualTo(NotificationRepository.SENT);
    }

    @Test
    @DisplayName("FR-5.2: a 200 whose vendor body says no is not a delivery")
    void aVendorRefusalBehindA200IsNotADelivery() {
        // A vendor channel, not the generic webhook: only the robots put the verdict in the body, and a receiver
        // that answers 200 with its own JSON has to be believed.
        rule(match("OOM"), action("dingtalk", sink.url("/robot?access_token=tok"), SECRET_REF));
        sink.respondWith(200, "{\"errcode\":45009,\"errmsg\":\"toomany\"}");
        publish("OOM at midnight");

        delivery.run(claim());

        long notificationId = notification(1);
        assertThat(row(notificationId).state()).isEqualTo(NotificationRepository.PENDING);
        assertThat(row(notificationId).attempts()).isEqualTo(1);
        assertThat(row(notificationId).httpStatus()).isEqualTo(200);
        assertThat(row(notificationId).error()).contains("45009");
        assertThat(taskState(notificationId)).isEqualTo("pending");
    }

    @Test
    @DisplayName("FR-5.2: a channel that refuses twice and then accepts ends up sent, with two failed attempts logged")
    void aChannelThatRefusesTwiceAndThenAcceptsEndsUpSent() {
        rule(match("OOM"), action("webhook", sink.url("/hook"), null));
        sink.failNextRequests(2);
        publish("OOM at midnight");

        Task task = claim();
        delivery.run(task);
        assertThat(fireAt(task.id())).isCloseTo(Instant.now().plus(Duration.ofMinutes(1)), within(LADDER_TOLERANCE));
        armNow(task.id());

        delivery.run(claim());
        assertThat(fireAt(task.id())).isCloseTo(Instant.now().plus(Duration.ofMinutes(5)), within(LADDER_TOLERANCE));
        armNow(task.id());

        delivery.run(claim());

        assertThat(sink.requests()).hasSize(3);
        assertThat(row(notification(1)).state()).isEqualTo(NotificationRepository.SENT);
        assertThat(row(notification(1)).attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("FR-5.3: the whole ladder runs at 1/5/15/60 minutes, then the row fails and the breaker stops the rule")
    void anUnreachableChannelRunsTheWholeLadderAndThenSilencesTheRule() {
        long ruleId = rule(match("OOM"), action("webhook", sink.url("/hook"), null));
        sink.respondWith(500, "{\"errmsg\":\"boom\"}");
        publish("OOM at midnight");
        long notificationId = notification(1);

        Duration[] ladder = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
            Duration.ofMinutes(60)};
        for (Duration wait : ladder) {
            Task task = claim();
            delivery.run(task);
            assertThat(fireAt(task.id())).as("wait after attempt " + task.attempts())
                .isCloseTo(Instant.now().plus(wait), within(LADDER_TOLERANCE));
            armNow(task.id());
        }
        delivery.run(claim());

        assertThat(row(notificationId).state()).isEqualTo(NotificationRepository.FAILED);
        assertThat(row(notificationId).attempts()).isEqualTo(5);
        assertThat(row(notificationId).error()).contains("HTTP 500");
        assertThat(taskState(notificationId)).isEqualTo("failed");

        assertThat(disabledReason(ruleId)).startsWith("delivery failed:");
        assertThat(enabled(ruleId)).isFalse();
        assertThat(rules.countAutoDisabled()).isEqualTo(1);

        // A rule the breaker stopped cannot keep judging: the next entry queues nothing.
        publish("another OOM");
        assertThat(notificationCount()).isEqualTo(1);
        assertThat(sink.requests()).hasSize(5);
    }

    @Test
    @DisplayName("FR-5.3: manual replay re-sends the body that was decided on, with a fresh attempt budget")
    void replayResendsTheBodyThatWasDecidedOn() {
        rule(match("OOM"), action("webhook", sink.url("/hook"), null));
        sink.respondWith(500, "{\"errmsg\":\"boom\"}");
        publish("OOM at midnight");
        long notificationId = notification(1);
        for (int attempt = 1; attempt <= 4; attempt++) {
            Task task = claim();
            delivery.run(task);
            armNow(task.id());
        }
        delivery.run(claim());
        assertThat(row(notificationId).state()).isEqualTo(NotificationRepository.FAILED);

        sink.respondWith(200, "{\"errcode\":0}");
        assertThat(delivery.replay(notificationId).state()).isEqualTo(NotificationRepository.PENDING);
        delivery.run(claim());

        assertThat(sink.requests()).hasSize(6);
        assertThat(json.readTree(sink.last().body()).path("title").asString()).isEqualTo("OOM at midnight");
        assertThat(row(notificationId).state()).isEqualTo(NotificationRepository.SENT);
        assertThat(row(notificationId).attempts()).isZero();
    }

    @Test
    @DisplayName("FR-5.3: only a failed notification can be replayed, and a row that never hit has nothing to resend")
    void onlyAFailedNotificationIsReplayable() {
        rule(match("OOM"), action("webhook", sink.url("/hook"), null));
        publish("OOM at midnight");
        delivery.run(claim());

        assertThatThrownBy(() -> delivery.replay(notification(1)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("not_replayable"));
        assertThatThrownBy(() -> delivery.replay(999_999L))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("unknown_notification"));
        assertThat(sink.requests()).hasSize(1);
    }

    @Test
    @DisplayName("FR-5.2: the second hit inside the suppression window leaves a record and sends nothing")
    void suppressionKeepsTheSecondHitOfTheSameSourceQuiet() {
        long ruleId = rule(match("OOM"), action("webhook", sink.url("/hook"), null), """
            {"key":"perFeed","hours":6}""");
        publish("OOM at midnight");
        delivery.run(claim());
        assertThat(sink.requests()).hasSize(1);

        publish("OOM again on the same feed");

        assertThat(sink.requests()).hasSize(1);
        long suppressed = notification(2);
        assertThat(row(suppressed).state()).isEqualTo(NotificationRepository.SUPPRESSED);
        assertThat(queue.findByDedupeKey("deliver", "deliver:" + suppressed)).isEmpty();
        // The hit still counts: suppression is a delivery decision, not a matching one.
        assertThat(hits(ruleId)).isEqualTo(2);
    }

    @Test
    @DisplayName("FR-5.2: a different content hash is a different hit under perContentHash")
    void suppressionGranularityPicksWhatCountsAsTheSameThing() {
        long ruleId = rule(match("OOM"), action("webhook", sink.url("/hook"), null), """
            {"key":"perContentHash","hours":6}""");
        publish("OOM at midnight");
        delivery.run(claim());

        publish("OOM at midnight, rewritten");

        assertThat(row(notification(2)).state()).isEqualTo(NotificationRepository.PENDING);
        assertThat(notificationCount()).isEqualTo(2);
        delivery.run(claim());
        assertThat(sink.requests()).hasSize(2);
        assertThat(hits(ruleId)).isEqualTo(2);
    }

    @Test
    @DisplayName("FR-5.2: quiet hours keep the inbox mark and hold the send back")
    void quietHoursRecordTheHitButSendNothing() {
        LocalTime nowUtc = LocalTime.now(ZoneOffset.UTC);
        long ruleId = rule(match("OOM"), action("webhook", sink.url("/hook"), null), """
            {"key":"perFeed","hours":0}""", """
            {"from":"%s","to":"%s","tz":"UTC"}""".formatted(nowUtc.minusMinutes(10), nowUtc.plusMinutes(10)));

        publish("OOM at midnight");

        assertThat(sink.requests()).isEmpty();
        long notificationId = notification(1);
        assertThat(row(notificationId).state()).isEqualTo(NotificationRepository.SKIPPED_QUIET);
        assertThat(queue.findByDedupeKey("deliver", "deliver:" + notificationId)).isEmpty();
        assertThat(notifications.failedAttemptRun(ruleId, 5)).isZero();
    }

    @Test
    @DisplayName("FR-5.1: markOnly marks the entry and never opens a connection")
    void markOnlyMarksAndNeverConnects() {
        rule(match("OOM"), "[{\"channel\":\"markOnly\"}]");
        publish("OOM at midnight");

        delivery.run(claim());

        assertThat(sink.requests()).isEmpty();
        assertThat(row(notification(1)).state()).isEqualTo(NotificationRepository.SENT);
        assertThat(row(notification(1)).httpStatus()).isNull();
    }

    @Test
    @DisplayName("FR-5.2: the generic webhook signs the exact bytes it posts")
    void aWebhookSignsTheBytesItSends() {
        rule(match("OOM"), action("webhook", sink.url("/hook"), SECRET_REF));
        publish("OOM at midnight");

        delivery.run(claim());

        String signature = sink.last().first("x-rss-hub-signature");
        assertThat(signature).startsWith("sha256=");
        assertThat(signature).isEqualTo("sha256=" + Signer.hmacSha256Hex(SECRET, sink.last().body()));
    }

    @Test
    @DisplayName("FR-5.2: DingTalk carries its 加签 in the query, and a secret that will not resolve stops the send")
    void dingtalkSignsItsUrlAndRefusesToSendWithoutASecret() {
        rule(match("OOM"), action("dingtalk", sink.url("/robot?access_token=tok"), SECRET_REF));
        publish("OOM at midnight");

        delivery.run(claim());

        assertThat(sink.last().path()).isEqualTo("/robot");
        assertThat(sink.last().query()).contains("access_token=tok").contains("timestamp=").contains("sign=");
        assertThat(json.readTree(sink.last().body()).path("msgtype").asString()).isEqualTo("text");
        assertThat(row(notification(1)).state()).isEqualTo(NotificationRepository.SENT);

        // A robot with no 加签 secret cannot be identified, so nothing goes out and the reason says what to fix.
        sink.clear();
        long secondRule = rule(match("OOM"), action("dingtalk", sink.url("/robot?access_token=tok"), "not-configured"),
            """
                {"key":"perFeed","hours":0}""");
        publish("OOM on the second rule");
        delivery.run(claim());

        assertThat(sink.requests()).isEmpty();
        assertThat(row(notificationFor(secondRule)).state()).isEqualTo(NotificationRepository.PENDING);
        assertThat(row(notificationFor(secondRule)).error()).contains("no signing secret");
        assertThat(enabled(secondRule)).isTrue();
    }

    @Test
    @DisplayName("FR-5.2: WeCom sends markdown, and a rate-limited answer keeps the row pending")
    void wecomSendsMarkdownAndRetriesARateLimitedAnswer() {
        long ruleId = rule(match("OOM"), action("wecom", sink.url("/send?key=k"), null));
        publish("OOM at midnight");
        delivery.run(claim());

        JsonNode body = json.readTree(sink.last().body());
        assertThat(body.path("msgtype").asString()).isEqualTo("markdown");
        assertThat(body.path("markdown").path("content").asString())
            .contains("### OOM at midnight")
            .contains("[打开原文](http://example.test/oom-1)");
        assertThat(row(notification(1)).state()).isEqualTo(NotificationRepository.SENT);

        sink.respondWith(200, "{\"errcode\":45009,\"errmsg\":\"from the sink\"}");
        // hours:0 on this rule only: the first rule still suppresses its own repeat hit, which would otherwise
        // make this entry's verdict depend on which row the evaluator visited first.
        long secondRule = rule(match("OOM"), action("wecom", sink.url("/send?key=k"), null), """
            {"key":"perFeed","hours":0}""");
        publish("OOM on the second rule");
        delivery.run(claim());

        long retried = notificationFor(secondRule);
        assertThat(row(retried).state()).isEqualTo(NotificationRepository.PENDING);
        assertThat(row(retried).error()).contains("45009");
        assertThat(taskState(retried)).isEqualTo("pending");
        assertThat(enabled(secondRule)).isTrue();
    }

    @Test
    @DisplayName("FR-5.3: the delivery log outlives the rule, and the inbox mark still names it")
    void theLogOutlivesTheRuleThatWroteIt() {
        long ruleId = rule(match("OOM"), action("webhook", sink.url("/hook"), null));
        publish("OOM at midnight");
        long entryId = lastEntryId();
        delivery.run(claim());
        rules.delete(ruleId);

        assertThat(row(notification(1)).ruleId()).isNull();
        assertThat(row(notification(1)).state()).isEqualTo(NotificationRepository.SENT);
        assertThat(notifications.ruleNamesByEntries(List.of(entryId)).get(entryId))
            .containsExactly(NotificationRepository.GONE_RULE_NAME);
    }

    private static String match(String keyword) {
        return "{\"includeAny\":[\"" + keyword + "\"]}";
    }

    /** Built through {@link JsonBody} so a null {@code secretRef} is a JSON null rather than the string "null". */
    private String action(String channel, String target, String secretRef) {
        return json.writeValueAsString(List.of(JsonBody.pairs("channel", channel, "target", target,
            "secretRef", secretRef)));
    }

    private long rule(String match, String actions) {
        return rule(match, actions, null, null);
    }

    private long rule(String match, String actions, String suppression) {
        return rule(match, actions, suppression, null);
    }

    private long rule(String match, String actions, String suppression, String quietHours) {
        return rules.insert("notify-rule-" + sequence.incrementAndGet(), match, actions, suppression, quietHours);
    }

    private void publish(String title) {
        int seq = sequence.incrementAndGet();
        db.sql("insert into entry (feed_id, guid, title, url, summary) values (?, ?, ?, ?, ?)")
            .params(feedId, "guid-" + seq, title, "http://example.test/oom-1", "a summary the channel will show")
            .update();
        long entryId = db.sql("select id from entry where guid = ?").param("guid-" + seq).query(Long.class).single();
        events.publishEvent(new EntryNewEvent(entryId, feedId, "Loopback Weekly", "生产",
            "http://example.test/oom-1", title, "a summary the channel will show", "content-hash-" + seq,
            List.of("P1"), Instant.now(), Instant.now()));
    }

    private Task claim() {
        return queue.claim("deliver", Instant.now(), "test-worker")
            .orElseThrow(() -> new AssertionError("nothing was queued to deliver"));
    }

    private NotificationRepository.Row row(long notificationId) {
        return notifications.find(notificationId).orElseThrow();
    }

    /** The n-th notification by id: the tests above count their own hits rather than guessing at sequences. */
    private long notification(int ordinal) {
        return db.sql("select id from notification_log order by id limit 1 offset " + (ordinal - 1))
            .query(Long.class)
            .single();
    }

    /**
     * The newest notification one rule wrote. A second rule in the same test matches the same entry, so counting
     * rows would read the other rule's verdict.
     */
    private long notificationFor(long ruleId) {
        return db.sql("select id from notification_log where rule_id = ? order by id desc limit 1")
            .param(ruleId)
            .query(Long.class)
            .single();
    }

    private long notificationCount() {
        return db.sql("select count(*) from notification_log").query(Long.class).single();
    }

    private long lastEntryId() {
        return db.sql("select id from entry order by id desc limit 1").query(Long.class).single();
    }

    private String taskState(long notificationId) {
        return db.sql("select state from scheduled_task where kind = 'deliver' and dedupe_key = ?")
            .param("deliver:" + notificationId)
            .query(String.class)
            .single();
    }

    private Instant fireAt(long taskId) {
        return db.sql("select fire_at from scheduled_task where id = ?").param(taskId)
            .query((rs, n) -> rs.getObject(1, OffsetDateTime.class).toInstant())
            .single();
    }

    private boolean enabled(long ruleId) {
        return db.sql("select enabled from rule where id = ?").param(ruleId).query(Boolean.class).single();
    }

    private String disabledReason(long ruleId) {
        return db.sql("select disabled_reason from rule where id = ?").param(ruleId).query(String.class).single();
    }

    private String ruleName(long ruleId) {
        return db.sql("select name from rule where id = ?").param(ruleId).query(String.class).single();
    }

    private int hits(long ruleId) {
        return db.sql("select hit_count from rule where id = ?").param(ruleId).query(Integer.class).single();
    }

    /** The ladder waits are minutes; the test pulls the row due instead of sleeping through them. */
    private void armNow(long taskId) {
        db.sql("update scheduled_task set fire_at = ? where id = ?")
            .params(JdbcTimes.toSql(Instant.now()), taskId)
            .update();
    }
}
