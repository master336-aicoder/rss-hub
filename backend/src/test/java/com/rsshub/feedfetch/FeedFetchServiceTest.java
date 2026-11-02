package com.rsshub.feedfetch;

import static com.rsshub.feedfetch.LocalFeedServer.FEED_BODY;
import static com.rsshub.feedfetch.LocalFeedServer.RSS_WITH_ETAG;
import static com.rsshub.feedfetch.LocalFeedServer.Scenario.after;
import static com.rsshub.feedfetch.LocalFeedServer.Scenario.chunked;
import static com.rsshub.feedfetch.LocalFeedServer.Scenario.of;
import static com.rsshub.feedfetch.LocalFeedServer.Scenario.ok;
import static com.rsshub.feedfetch.LocalFeedServer.Scenario.redirect;
import static com.rsshub.feedfetch.LocalFeedServer.Scenario.withHeaders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.rsshub.feedfetch.TaskQueue.Task;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 03 §4.3 written as an executable decision table, driven over real HTTP against a loopback publisher. MockMvc
 * could not prove any of it: the subject is an outbound client, not an inbound controller.
 *
 * <p>The context runs with {@code rsshub.fetch.enabled=false} so the scheduler never competes for the rows these
 * tests claim, {@code per-host-gap=0s} so back-to-back rounds on one host are not polite about it (the gap has its
 * own unit test), and {@code jitter=0s} so the next fire time is arithmetic rather than a range.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:feedfetch;DB_CLOSE_DELAY=-1",
    "rsshub.operator.username=admin",
    "rsshub.operator.password=a-test-only-password",
    "rsshub.fetch.enabled=false",
    "rsshub.fetch.interval=60m",
    "rsshub.fetch.min-interval=5m",
    "rsshub.fetch.jitter=0s",
    "rsshub.fetch.connect-timeout=1s",
    "rsshub.fetch.read-timeout=2s",
    "rsshub.fetch.max-bytes=4096",
    "rsshub.fetch.per-host-gap=0s",
    "rsshub.fetch.auth-backoff=24h",
    "rsshub.fetch.backoff-base=1m",
    "rsshub.fetch.backoff-cap=24h",
    "rsshub.fetch.notfound-gone-days=30d",
    "rsshub.ssrf.allow-private-prefixes[0]=127.0.0.1",
    "rsshub.storage.raw-dir=target/test-raw"
})
class FeedFetchServiceTest {

    private static final Duration SLACK = Duration.ofSeconds(5);

    @Autowired
    FeedFetchService service;
    @Autowired
    TaskQueue queue;
    @Autowired
    JdbcClient db;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StorageProperties storage;

    private LocalFeedServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = new LocalFeedServer();
        // The context and therefore the in-memory database are shared across tests; claim() picks the globally
        // earliest due row, so leftovers from a previous test would be fetched instead of the one under test.
        jdbc.update("delete from raw_response");
        jdbc.update("delete from scheduled_task");
        jdbc.update("delete from feed");
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void contentResetsCountersStoresValidatorsAndKeepsBytes() {
        long feedId = feed(server.url("/feed.xml"));
        setFeed(feedId, "fail_count", 3);
        setFeed(feedId, "notfound_streak", 2);
        Instant before = Instant.now();

        run(feedId);

        assertThat(text(feedId, "state")).isEqualTo("active");
        assertThat(num(feedId, "fail_count")).isZero();
        assertThat(num(feedId, "notfound_streak")).isZero();
        assertThat(text(feedId, "last_error")).isNull();
        assertThat(num(feedId, "last_http_status")).isEqualTo(200);
        assertThat(text(feedId, "etag")).isEqualTo("\"v1\"");
        assertThat(instant(feedId, "last_fetched_at")).isCloseTo(before, within(SLACK));
        assertThat(num(feedId, "avg_fetch_ms")).isNotNull();

        RawRow raw = rawRow(feedId);
        assertThat(raw.byteSize()).isEqualTo(FEED_BODY.length());
        assertThat(raw.sha256()).hasSize(64);
        assertThat(Files.exists(storage.rawDir().resolve(raw.storageKey()))).isTrue();

        assertThat(onlyTask(feedId).state()).isEqualTo("pending");
        assertThat(onlyTask(feedId).fireAt()).isCloseTo(before.plus(Duration.ofMinutes(60)), within(SLACK));
    }

    @Test
    void storedValidatorsAreSentOnTheNextRound() {
        long feedId = feed(server.url("/feed.xml"), etag("\"v7\""), lastModified(Instant.EPOCH));

        run(feedId);

        LocalFeedServer.Received sent = server.lastRequest();
        assertThat(sent.first("if-none-match")).isEqualTo("\"v7\"");
        assertThat(sent.first("if-modified-since")).isNotNull();
        assertThat(sent.first("accept")).contains("application/rss+xml");
        assertThat(sent.first("user-agent")).startsWith("rss-hub/");
        // java.net.http sets and decodes gzip itself; asking by hand would hand the parser compressed bytes.
        assertThat(sent.first("accept-encoding")).isNull();
    }

    @Test
    void notModifiedKeepsValidatorsResetsFailsAndStoresNothing() {
        long feedId = feed(server.url("/feed.xml"), fail(4), streak(1), etag("\"keep\""));
        server.setDefault(of(304));
        Instant before = Instant.now();

        run(feedId);

        assertThat(text(feedId, "state")).isEqualTo("active");
        assertThat(num(feedId, "fail_count")).isZero();
        // A 304 is proof the address is still alive, so the 404 streak breaks here too — "gone" means every round.
        assertThat(num(feedId, "notfound_streak")).isZero();
        assertThat(num(feedId, "last_http_status")).isEqualTo(304);
        assertThat(text(feedId, "etag")).isEqualTo("\"keep\"");
        assertThat(rawCount(feedId)).isZero();
        assertThat(onlyTask(feedId).fireAt()).isCloseTo(before.plus(Duration.ofMinutes(60)), within(SLACK));
    }

    @Test
    void permanentMoveRewritesCanonicalUrl() {
        long feedId = feed(server.url("/old.xml"));
        String movedUrl = server.url("/moved.xml");
        server.on("/old.xml", redirect(301, movedUrl));

        run(feedId);

        assertThat(text(feedId, "canonical_url")).isEqualTo(movedUrl);
        assertThat(text(feedId, "state")).isEqualTo("active");
        assertThat(num(feedId, "last_http_status")).isEqualTo(200);
    }

    @Test
    void temporaryMoveIsFollowedButNotBelieved() {
        long feedId = feed(server.url("/old.xml"));
        server.on("/old.xml", redirect(302, server.url("/tmp.xml")));

        run(feedId);

        assertThat(text(feedId, "canonical_url")).isEqualTo(server.url("/old.xml"));
        assertThat(num(feedId, "last_http_status")).isEqualTo(200);
        assertThat(server.requests()).hasSize(2);
    }

    @Test
    void endlessRedirectStopsAtTheLimit() {
        long feedId = feed(server.url("/loop"));
        server.on("/loop", redirect(302, server.url("/loop")));

        run(feedId);

        assertThat(text(feedId, "state")).isEqualTo("error");
        assertThat(text(feedId, "last_error")).isEqualTo("redirect_limit");
        assertThat(num(feedId, "fail_count")).isEqualTo(1);
        // max-redirects=3 means the fourth answer is still a redirect and is refused.
        assertThat(server.requests()).hasSize(4);
    }

    @Test
    void gone410CancelsTheTaskAndMarksTheFeed() {
        long feedId = feed(server.url("/feed.xml"));
        server.setDefault(of(410));

        run(feedId);

        assertThat(text(feedId, "state")).isEqualTo("gone");
        assertThat(num(feedId, "last_http_status")).isEqualTo(410);
        assertThat(onlyTask(feedId).state()).isEqualTo("cancelled");
        assertThat(onlyTask(feedId).error()).isEqualTo("HTTP 410");
    }

    @Test
    void repeatedNotFoundStreaksThenGoneAfterThirtyDays() {
        long feedId = feed(server.url("/feed.xml"));
        server.setDefault(of(404));

        run(feedId);

        assertThat(num(feedId, "notfound_streak")).isEqualTo(1);
        assertThat(num(feedId, "fail_count")).isEqualTo(1);
        assertThat(text(feedId, "state")).isEqualTo("error");
        assertThat(onlyTask(feedId).state()).isEqualTo("pending");
        // base 1m doubled once, because fail_count is now 1
        assertThat(onlyTask(feedId).fireAt()).isCloseTo(Instant.now().plus(Duration.ofMinutes(2)), within(SLACK));

        // 720 hourly rounds is exactly the 30-day threshold in rsshub.fetch.notfound-gone-days
        setFeed(feedId, "notfound_streak", 720);
        setFeed(feedId, "fail_count", 0);
        run(feedId);

        assertThat(text(feedId, "state")).isEqualTo("gone");
        assertThat(onlyTask(feedId).state()).isEqualTo("cancelled");
    }

    @Test
    void rateLimitSecondsAreHonouredWithoutCountingAFailure() {
        long feedId = feed(server.url("/feed.xml"), fail(2));
        server.setDefault(withHeaders(429, Map.of("Retry-After", "120")));
        Instant before = Instant.now();

        run(feedId);

        assertThat(num(feedId, "fail_count")).isEqualTo(2);
        assertThat(text(feedId, "last_error")).isEqualTo("rate_limited");
        assertThat(text(feedId, "state")).isEqualTo("active");
        assertThat(onlyTask(feedId).fireAt()).isCloseTo(before.plus(Duration.ofSeconds(120)), within(SLACK));
    }

    @Test
    void rateLimitHttpDateIsHonoured() {
        long feedId = feed(server.url("/feed.xml"));
        server.setDefault(withHeaders(429, Map.of("Retry-After", DateTimeFormatter.RFC_1123_DATE_TIME
            .format(java.time.ZonedDateTime.ofInstant(Instant.now().plus(Duration.ofMinutes(10)), ZoneOffset.UTC)))));

        run(feedId);

        assertThat(onlyTask(feedId).fireAt()).isCloseTo(Instant.now().plus(Duration.ofMinutes(10)),
            within(Duration.ofSeconds(3)));
    }

    @Test
    void absurdRetryAfterIsCappedAtTheBackoffCeiling() {
        long feedId = feed(server.url("/feed.xml"));
        server.setDefault(withHeaders(429, Map.of("Retry-After", "999999999")));

        run(feedId);

        assertThat(onlyTask(feedId).fireAt()).isCloseTo(Instant.now().plus(Duration.ofHours(24)), within(SLACK));
    }

    @Test
    void authRequiredStaysAwayForADay() {
        long feedId = feed(server.url("/feed.xml"));
        server.setDefault(of(403));

        run(feedId);

        assertThat(text(feedId, "state")).isEqualTo("error");
        assertThat(text(feedId, "last_error")).isEqualTo("auth_required");
        assertThat(num(feedId, "fail_count")).isZero();
        assertThat(onlyTask(feedId).fireAt()).isCloseTo(Instant.now().plus(Duration.ofHours(24)), within(SLACK));
    }

    @Test
    void serverErrorBacksOffExponentiallyAndResetsOnSuccess() {
        long feedId = feed(server.url("/feed.xml"), fail(2));
        server.setDefault(of(503));

        run(feedId);

        assertThat(num(feedId, "fail_count")).isEqualTo(3);
        assertThat(onlyTask(feedId).fireAt()).isCloseTo(Instant.now().plus(Duration.ofMinutes(8)), within(SLACK));

        server.setDefault(ok(FEED_BODY, RSS_WITH_ETAG));
        run(feedId);
        assertThat(num(feedId, "fail_count")).isZero();
    }

    @Test
    void timeoutCountsAsASourceFailure() {
        long feedId = feed(server.url("/slow"));
        server.setDefault(after(Duration.ofSeconds(6), 200));

        run(feedId);

        assertThat(text(feedId, "state")).isEqualTo("error");
        assertThat(text(feedId, "last_error")).isEqualTo("timeout");
        assertThat(num(feedId, "fail_count")).isEqualTo(1);
        assertThat(rawCount(feedId)).isZero();
    }

    @Test
    void streamedOversizeBodyIsOursNotTheSources() {
        long feedId = feed(server.url("/big"), fail(1));
        server.setDefault(chunked(200, 64 * 1024));

        run(feedId);

        assertThat(num(feedId, "fail_count")).isEqualTo(1);
        assertThat(text(feedId, "last_error")).isEqualTo("size_limit");
        // half of backoffFor(max(1,1)) = 2m, so one minute
        assertThat(onlyTask(feedId).fireAt()).isCloseTo(Instant.now().plus(Duration.ofMinutes(1)), within(SLACK));
        assertThat(rawCount(feedId)).isZero();
    }

    @Test
    void declaredOversizeBodyIsRejectedBeforeReading() {
        long feedId = feed(server.url("/huge"));
        server.setDefault(ok("x".repeat(64 * 1024), RSS_WITH_ETAG));

        run(feedId);

        assertThat(text(feedId, "last_error")).isEqualTo("size_limit");
        assertThat(num(feedId, "fail_count")).isZero();
        assertThat(rawCount(feedId)).isZero();
    }

    @Test
    void htmlAnswerIsKeptForDiagnosisAndFlagged() {
        long feedId = feed(server.url("/"));
        server.setDefault(ok("<html><body>a page</body></html>", Map.of("Content-Type", "text/html")));

        run(feedId);

        assertThat(text(feedId, "state")).isEqualTo("error");
        assertThat(text(feedId, "last_error")).isEqualTo("not_a_feed");
        assertThat(num(feedId, "fail_count")).isZero();
        assertThat(rawCount(feedId)).isEqualTo(1);
    }

    @Test
    void privateMetadataTargetIsRefusedBeforeConnecting() {
        long feedId = feed("http://169.254.169.254/latest/meta-data");

        run(feedId);

        assertThat(text(feedId, "state")).isEqualTo("error");
        assertThat(text(feedId, "last_error")).startsWith("ssrf_blocked:");
        assertThat(rawCount(feedId)).isZero();
        assertThat(onlyTask(feedId).fireAt()).isCloseTo(Instant.now().plus(Duration.ofHours(24)), within(SLACK));
    }

    @Test
    void redirectOntoAPrivateAddressIsRefusedAtThatHop() {
        // TC-SEC-04: each hop is a new target, so the guard runs again rather than trusting the first answer. An
        // automatic redirect in the HTTP client would have opened that socket before anyone looked at the address.
        long feedId = feed(server.url("/to-metadata"));
        server.on("/to-metadata", redirect(302, "http://169.254.169.254/latest/meta-data"));

        run(feedId);

        assertThat(text(feedId, "last_error")).startsWith("ssrf_blocked:");
        assertThat(text(feedId, "state")).isEqualTo("error");
        // The publisher answered once, and the metadata address was never contacted.
        assertThat(server.requests()).hasSize(1);
        assertThat(rawCount(feedId)).isZero();
        assertThat(text(feedId, "canonical_url")).isEqualTo(server.url("/to-metadata"));
    }

    @Test
    void feedAdvertisedTtlWinsButRespectsTheFloor() {
        // canonical_url is unique, so the two subscriptions need two paths; both serve the same default feed body.
        long fast = feed(server.url("/fast.xml"), ttl(1));
        long slow = feed(server.url("/slow.xml"), ttl(120));

        run(fast);
        assertThat(onlyTask(fast).fireAt()).isCloseTo(Instant.now().plus(Duration.ofMinutes(5)), within(SLACK));

        run(slow);
        assertThat(onlyTask(slow).fireAt()).isCloseTo(Instant.now().plus(Duration.ofMinutes(120)), within(SLACK));
    }

    /**
     * The operator's own cadence, which is what "默认一小时一轮，支持手动设置" means: it outranks the document's
     * {@code <ttl>} and is still floored, so setting 1 cannot turn one subscription into a polling loop.
     */
    @Test
    void theOperatorsIntervalBeatsTheAdvertisedTtlButNotTheFloor() {
        long overridden = feed(server.url("/mine.xml"), ttl(120), interval(15));
        long floored = feed(server.url("/hungry.xml"), ttl(120), interval(1));
        // 0 is how the UI spells "stop overriding"; the site's 120 has to come back, not a zero-minute round.
        long cleared = feed(server.url("/back-to-site.xml"), ttl(120), interval(0));

        run(overridden);
        assertThat(onlyTask(overridden).fireAt()).isCloseTo(Instant.now().plus(Duration.ofMinutes(15)), within(SLACK));

        run(floored);
        assertThat(onlyTask(floored).fireAt()).isCloseTo(Instant.now().plus(Duration.ofMinutes(5)), within(SLACK));

        run(cleared);
        assertThat(onlyTask(cleared).fireAt())
            .isCloseTo(Instant.now().plus(Duration.ofMinutes(120)), within(SLACK));
    }

    @Test
    void pausedFeedParksItsTaskWithoutARequest() {
        long feedId = feed(server.url("/feed.xml"), state("paused"));

        run(feedId);

        assertThat(onlyTask(feedId).state()).isEqualTo("cancelled");
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void deletedFeedTakesItsTaskWithIt() {
        long feedId = feed(server.url("/feed.xml"));
        Task task = claim(feedId);
        db.sql("delete from feed where id = ?").param(feedId).update();

        service.run(task);

        assertThat(tasks()).isZero();
    }

    // --- fixtures ------------------------------------------------------------------------------------------

    /**
     * The extras are declared as {@code Object[]...} rather than {@code Object...} on purpose: a single
     * {@code new Object[] {"fail_count", 3}} passed to {@code Object...} IS the varargs array, and the loop below
     * would then try to cast the column name to an array.
     */
    private long feed(String url, Object[]... columnAndValue) {
        db.sql("insert into feed (canonical_url, requested_url) values (?, ?)").params(url, url).update();
        // No generated keys here either: H2 returns every column that has a default from getGeneratedKeys(), so the
        // identity is not a single key. canonical_url is unique, so reading the row back is the dialect-neutral way.
        long id = db.sql("select max(id) from feed where canonical_url = ?").param(url).query(Long.class).single();
        for (Object[] pair : columnAndValue) {
            setFeed(id, (String) pair[0], pair[1]);
        }
        return id;
    }

    private void setFeed(long feedId, String column, Object value) {
        jdbc.update("update feed set " + column + " = ? where id = ?", value, feedId);
    }

    private Object[] fail(int value) {
        return new Object[] {"fail_count", value};
    }

    private Object[] streak(int value) {
        return new Object[] {"notfound_streak", value};
    }

    private Object[] etag(String value) {
        return new Object[] {"etag", value};
    }

    private Object[] lastModified(Instant value) {
        return new Object[] {"last_modified_hdr",
            DateTimeFormatter.RFC_1123_DATE_TIME.format(value.atZone(ZoneOffset.UTC))};
    }

    private Object[] ttl(int minutes) {
        return new Object[] {"ttl_minutes", minutes};
    }

    /** The operator's column, written directly because the API path that sets it is {@code InboxApiTest}'s subject. */
    private Object[] interval(int minutes) {
        return new Object[] {"fetch_interval_minutes", minutes};
    }

    private Object[] state(String value) {
        return new Object[] {"state", value};
    }

    private void run(long feedId) {
        service.run(claim(feedId));
    }

    /** enqueue + scheduleNow is the same pair the refresh button will use, so these tests ride that path too. */
    private Task claim(long feedId) {
        queue.enqueue("fetch", "feed:" + feedId, feedId, Instant.now());
        queue.scheduleNow("fetch", feedId, Instant.now().minusSeconds(1));
        return queue.claim("fetch", Instant.now(), "test-worker")
            .orElseThrow(() -> new IllegalStateException("nothing claimable for feed " + feedId));
    }

    private TaskView onlyTask(long feedId) {
        return db.sql("select state, fire_at, last_error from scheduled_task where kind = 'fetch' and dedupe_key = ?")
            .param("feed:" + feedId)
            .query((rs, n) -> new TaskView(rs.getString("state"),
                rs.getObject("fire_at", OffsetDateTime.class).toInstant(), rs.getString("last_error")))
            .single();
    }

    private record TaskView(String state, Instant fireAt, String error) {}

    private RawRow rawRow(long feedId) {
        return db.sql("select byte_size, sha256, storage_key from raw_response where feed_id = ?").param(feedId)
            .query((rs, n) -> new RawRow(rs.getInt("byte_size"), rs.getString("sha256"),
                rs.getString("storage_key")))
            .single();
    }

    private record RawRow(int byteSize, String sha256, String storageKey) {}

    /**
     * Column names in the readers below are always literals from this file; nothing here is user input.
     * They go through JdbcTemplate rather than {@code JdbcClient.query(Integer.class)} because a SQL NULL is a
     * meaningful answer here ("the error was cleared") and the scalar single-column reader refuses it.
     */
    private String text(long feedId, String column) {
        return jdbc.query("select " + column + " from feed where id = ?", (rs, n) -> rs.getString(1), feedId).get(0);
    }

    private Integer num(long feedId, String column) {
        return jdbc.query("select " + column + " from feed where id = ?",
            (rs, n) -> rs.getObject(1, Integer.class), feedId).get(0);
    }

    private Instant instant(long feedId, String column) {
        OffsetDateTime value = jdbc.query("select " + column + " from feed where id = ?",
            (rs, n) -> rs.getObject(1, OffsetDateTime.class), feedId).get(0);
        return value == null ? null : value.toInstant();
    }

    private long rawCount(long feedId) {
        return jdbc.queryForObject("select count(*) from raw_response where feed_id = ?", Long.class, feedId);
    }

    private long tasks() {
        return jdbc.queryForObject("select count(*) from scheduled_task where kind = 'fetch'", Long.class);
    }
}
