package com.rsshub.adminapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rsshub.common.JdbcTimes;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * FR-5.1 to FR-5.4 over HTTP: the rules editor's contract, the delivery log the operator reads, and the two marks
 * the rest of the UI takes from them.
 *
 * <p>The notifier is switched off here on purpose. {@code RuleDeliveryTest} owns what a channel does with a payload;
 * this class owns what the API stores, refuses and reports — so a hit that would queue a send is asserted as a row
 * in {@code notification_log}, never as a request to a receiver.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:rulesapi;DB_CLOSE_DELAY=-1",
    "rsshub.operator.username=admin",
    "rsshub.operator.password=a-test-only-password",
    "rsshub.fetch.enabled=false",
    "rsshub.notify.enabled=false"
})
@AutoConfigureMockMvc
class RulesApiTest {

    private static final String PASSWORD = "a-test-only-password";
    private static final String HOOK = "https://hook.example.org/alerts";
    private static final String GONE = "（规则已删除）";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient db;

    @Autowired
    ObjectMapper json;

    private MockHttpSession session;

    @BeforeEach
    void startFromAnEmptyRuleSet() throws Exception {
        db.sql("delete from notification_log").update();
        db.sql("delete from scheduled_task").update();
        db.sql("delete from rule").update();
        db.sql("delete from entry").update();
        db.sql("delete from feed").update();

        session = new MockHttpSession();
        mvc.perform(loginInto(session)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("FR-5.1: a rule is created, read back decoded, and a PATCH leaves the columns it did not send")
    void aRuleRoundTripsItsJsonColumns() throws Exception {
        JsonNode created = json.readTree(text(mvc.perform(write(post("/api/rules"), Map.of(
            "name", "生产 OOM",
            "match", Map.of("includeAny", List.of("OOM"), "groups", List.of("生产")),
            "actions", List.of(Map.of("channel", "webhook", "target", HOOK, "secretRef", "ops")))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.enabled").value(true))
            .andReturn()));
        long id = created.get("id").asLong();

        // The UI must not need a second parser: the four JSON columns come back as documents, not as strings.
        assertThat(created.get("match").get("includeAny").get(0).asString()).isEqualTo("OOM");
        assertThat(created.get("actions").get(0).get("target").asString()).isEqualTo(HOOK);
        assertThat(created.get("actions").get(0).get("secretRef").asString()).isEqualTo("ops");
        // An absent suppression is the DDL default, not a hole: 03 §8.2's perFeed/6h is what the editor shows.
        assertThat(created.get("suppression").get("key").asString()).isEqualTo("perFeed");
        assertThat(created.get("suppression").get("hours").asInt()).isEqualTo(6);
        assertThat(created.get("quietHours")).as("non_null inclusion: no quiet window means no field").isNull();
        assertThat(created.get("hitCount").asInt()).isZero();
        assertThat(created.get("disabledReason")).isNull();

        JsonNode edited = json.readTree(text(mvc.perform(write(patch("/api/rules/" + id),
            Map.of("suppression", Map.of("key", "perTitleNorm", "hours", 1))))
            .andExpect(status().isOk())
            .andReturn()));
        assertThat(edited.get("match").get("groups").get(0).asString()).isEqualTo("生产");
        assertThat(edited.get("actions").get(0).get("channel").asString()).isEqualTo("webhook");
        assertThat(edited.get("name").asString()).isEqualTo("生产 OOM");
        assertThat(edited.get("suppression").get("key").asString()).isEqualTo("perTitleNorm");
        assertThat(edited.get("enabled").asBoolean()).isTrue();

        // The quiet window is the one column the editor can genuinely switch off, and an empty object — not an
        // absent field — is how it says so.
        mvc.perform(write(patch("/api/rules/" + id), Map.of("quietHours",
            Map.of("from", "23:00", "to", "07:00", "tz", "Asia/Shanghai"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.quietHours.from").value("23:00"));
        mvc.perform(write(patch("/api/rules/" + id), Map.of("quietHours", Map.of())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.quietHours").doesNotExist());
        assertThat(db.sql("select quiet_hours from rule where id = ?").param(id).query(String.class).optional())
            .as("clearing the window stores no column value, so the next GET has no field to echo").isEmpty();
        // A half-written window is still a mistake, not a way to turn it off.
        mvc.perform(write(patch("/api/rules/" + id), Map.of("quietHours", Map.of("from", "23:00"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:quiet_hours_incomplete"));

        // The editor's own route: opening a saved rule is a single GET, and it must hand back the same document the
        // PATCH wrote, not only the list the sidebar reads.
        JsonNode opened = json.readTree(text(mvc.perform(csrfProtected(get("/api/rules/" + id).session(session)))
            .andExpect(status().isOk())
            .andReturn()));
        assertThat(opened.get("id").asLong()).isEqualTo(id);
        assertThat(opened.get("name").asString()).isEqualTo("生产 OOM");
        assertThat(opened.get("actions").get(0).get("channel").asString()).isEqualTo("webhook");
        assertThat(opened.get("suppression").get("key").asString()).isEqualTo("perTitleNorm");
        assertThat(opened.get("quietHours")).as("the cleared window has no field to echo back").isNull();
        mvc.perform(csrfProtected(get("/api/rules").session(session))).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("生产 OOM"));
        mvc.perform(csrfProtected(get("/api/rules/" + (id + 1000)).session(session)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_rule"));

        mvc.perform(csrfProtected(delete("/api/rules/" + id).session(session))).andExpect(status().isNoContent());
        mvc.perform(csrfProtected(delete("/api/rules/" + id).session(session)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_rule"));
    }

    @Test
    @DisplayName("FR-5.1/5.3: every way to write a bad rule is a 400 that names the field")
    void validationRefusesTheMistakesTheEditorCanMake() throws Exception {
        // 03 §8.3's channels are the ones with an adapter; email is a documented deviation, not a typo.
        writeAction(Map.of("channel", "carrier-pigeon", "target", HOOK)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_channel"));
        writeAction(Map.of("channel", "webhook")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:target_required"));
        writeAction(Map.of("channel", "webhook", "target", "ftp://hook.example.org/a"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:target_scheme"));
        // markOnly is the channel with no target, and it is legal precisely because of that.
        mvc.perform(write(post("/api/rules"), Map.of("name", "只标记",
            "match", Map.of("includeAny", List.of("OOM")),
            "actions", List.of(Map.of("channel", "markOnly")))))
            .andExpect(status().isCreated());

        mvc.perform(write(post("/api/rules"), Map.of("name", "  ",
            "match", Map.of("includeAny", List.of("OOM")),
            "actions", List.of(Map.of("channel", "markOnly")))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:name_required"));
        mvc.perform(write(post("/api/rules"), Map.of("name", "a".repeat(256),
            "match", Map.of("includeAny", List.of("OOM")),
            "actions", List.of(Map.of("channel", "markOnly")))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:name_too_long"));
        mvc.perform(write(post("/api/rules"), Map.of("name", "只标记",
            "match", Map.of("includeAny", List.of("OOM")),
            "actions", List.of(Map.of("channel", "markOnly")))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("urn:rsshub:rule_exists"));
        // A rule with no actions would record a hit and speak about nothing.
        mvc.perform(write(post("/api/rules"), Map.of("name", "无动作",
            "match", Map.of("includeAny", List.of("OOM")))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:actions_required"));
        mvc.perform(write(post("/api/rules"), Map.of("name", "无匹配",
            "actions", List.of(Map.of("channel", "markOnly")))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:match_required"));
        mvc.perform(write(post("/api/rules"), Map.of("name", "空条件", "match", Map.of(),
            "actions", List.of(Map.of("channel", "markOnly")))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:match_too_broad"));
        // 03 §8.1's regex guards: a shape that can hang, one that cannot compile, one that is too long to trust.
        regex("(a+)+");
        regex("(");
        regex("a".repeat(201));
        mvc.perform(write(post("/api/rules"), Map.of("name", "超长关键词",
            "match", Map.of("includeAny", List.of("啊".repeat(201))),
            "actions", List.of(Map.of("channel", "markOnly")))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:keyword_too_long"));

        mvc.perform(write(post("/api/rules"), Map.of("name", "抑制粒度",
            "match", Map.of("includeAny", List.of("OOM")),
            "actions", List.of(Map.of("channel", "markOnly")),
            "suppression", Map.of("key", "perEntry", "hours", 6))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_suppression_key"));
        mvc.perform(write(post("/api/rules"), Map.of("name", "窗口",
            "match", Map.of("includeAny", List.of("OOM"), "windowMinutes", 43201),
            "actions", List.of(Map.of("channel", "markOnly")))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:invalid_windowMinutes"));
        mvc.perform(write(post("/api/rules"), Map.of("name", "静默",
            "match", Map.of("includeAny", List.of("OOM")),
            "actions", List.of(Map.of("channel", "markOnly")),
            "quietHours", Map.of("from", "23:00", "to", "07:00"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:quiet_hours_incomplete"));
        mvc.perform(write(post("/api/rules"), Map.of("name", "静默",
            "match", Map.of("includeAny", List.of("OOM")),
            "actions", List.of(Map.of("channel", "markOnly")),
            "quietHours", Map.of("from", "23:00", "to", "07:00", "tz", "Mars/Olympus"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_tz"));
        // A legal quiet window saves, and the round trip proves the editor can show it again.
        mvc.perform(write(post("/api/rules"), Map.of("name", "夜间静默",
            "match", Map.of("includeAny", List.of("OOM")),
            "actions", List.of(Map.of("channel", "markOnly")),
            "quietHours", Map.of("from", "23:00", "to", "07:00", "tz", "Asia/Shanghai"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.quietHours.tz").value("Asia/Shanghai"));

        assertThat(db.sql("select count(*) from rule").query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    @DisplayName("FR-5.3: enabling is its own decision, and a save cannot walk past the circuit breaker")
    void turningARuleOnAndOffIsNotASideEffectOfSavingIt() throws Exception {
        long id = createRule("开关", Map.of("includeAny", List.of("OOM")));
        db.sql("update rule set enabled = FALSE, disabled_reason = ? where id = ?")
            .params("delivery failed: task 9: HTTP 500 from " + HOOK, id).update();

        // The UI's save button posts the whole rule back, enabled included: the breaker's decision stands until the
        // operator reverses it on the route that is named for it.
        mvc.perform(write(patch("/api/rules/" + id), Map.of("name", "开关改名")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.disabledReason").value(containsString("delivery failed")));
        assertThat(db.sql("select count(*) from rule where enabled = FALSE and disabled_reason like ?")
            .param("delivery failed%").query(Long.class).single()).isEqualTo(1L);

        mvc.perform(write(patch("/api/rules/" + id + "/enabled"), Map.of("enabled", true)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.disabledReason").doesNotExist());
        assertThat(db.sql("select disabled_reason from rule where id = ?").param(id).query(String.class).optional())
            .isEmpty();

        mvc.perform(write(patch("/api/rules/" + id + "/enabled"), Map.of("enabled", false)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false));
        // A hand switch-off keeps no delivery reason, and the health page must not count it as a breaker trip.
        assertThat(db.sql("select count(*) from rule where enabled = FALSE and disabled_reason like ?")
            .param("delivery failed%").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("FR-5.4: the test button previews the last entries and records or sends nothing")
    void thePreviewJudgesWithoutLeavingATrace() throws Exception {
        long feedId = feed();
        entry(feedId, "OOM on node-3");
        entry(feedId, "disk capacity warning");
        entry(feedId, "panic: OOM killer invoked");
        long id = createRule("预览", Map.of("includeAny", List.of("OOM")));

        JsonNode preview = json.readTree(text(mvc.perform(write(post("/api/rules/" + id + "/test")))
            .andExpect(status().isOk())
            .andReturn()));
        assertThat(preview.get("scanned").asInt()).isEqualTo(3);
        assertThat(preview.get("matched").asInt()).isEqualTo(2);
        assertThat(preview.get("hits").valueStream().map(hit -> hit.get("title").asString()).toList())
            .containsExactlyInAnyOrder("OOM on node-3", "panic: OOM killer invoked");
        assertThat(preview.get("hits").get(0).get("keywords").get(0).asString()).isEqualTo("OOM");
        assertThat(preview.get("hits").get(0).get("feedTitle").asString()).isEqualTo("Loopback Weekly");

        // Nothing is written and nothing is queued: that is what makes the button safe on a live rule.
        assertThat(db.sql("select count(*) from notification_log").query(Long.class).single()).isZero();
        assertThat(db.sql("select count(*) from scheduled_task").query(Long.class).single()).isZero();
        assertThat(db.sql("select hit_count from rule where id = ?").param(id).query(Integer.class).single()).isZero();

        // The ceiling is the sample the design promises, not whatever the query string asked for.
        json.readTree(text(mvc.perform(write(post("/api/rules/" + id + "/test?limit=100000")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scanned").value(3))
            .andReturn()));
        assertThat(json.readTree(text(mvc.perform(write(post("/api/rules/" + id + "/test?limit=1")))
            .andExpect(status().isOk())
            .andReturn())).get("scanned").asInt()).isEqualTo(1);

        mvc.perform(write(post("/api/rules/999999/test")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_rule"));
    }

    @Test
    @DisplayName("FR-5.3: the delivery log filters by state and rule, and still names a rule that is gone")
    void theDeliveryLogReportsEveryVerdict() throws Exception {
        long feedId = feed();
        long alerted = entry(feedId, "OOM on node-3");
        long quiet = entry(feedId, "disk capacity warning");
        long ruleId = createRule("磁盘", Map.of("includeAny", List.of("OOM")));
        notification(ruleId, alerted, "webhook", "sent", 200, null, 1);
        notification(ruleId, alerted, "webhook", "suppressed", null, null, 0);
        long failedId = notification(ruleId, quiet, "dingtalk", "failed", 500, "HTTP 500 from the receiver", 5);

        JsonNode all = notifications("");
        assertThat(all.size()).isEqualTo(3);
        // Newest first, with the reason the operator needs and the rule that made the decision.
        assertThat(all.get(0).get("id").asLong()).isEqualTo(failedId);
        assertThat(all.get(0).get("ruleName").asString()).isEqualTo("磁盘");
        assertThat(all.get(0).get("error").asString()).contains("HTTP 500");
        assertThat(all.get(0).get("attempts").asInt()).isEqualTo(5);
        assertThat(all.get(0).get("createdAt").asString()).isNotBlank();

        assertThat(notifications("?state=suppressed").size()).isEqualTo(1);
        assertThat(notifications("?ruleId=" + ruleId + "&state=sent").size()).isEqualTo(1);
        assertThat(notifications("?limit=2").size()).isEqualTo(2);
        // A state that does not exist is a typo in the query string, and an empty list would hide it.
        mvc.perform(csrfProtected(get("/api/notifications?state=delivered").session(session)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_state"));

        mvc.perform(csrfProtected(delete("/api/rules/" + ruleId).session(session))).andExpect(status().isNoContent());
        JsonNode afterDeletion = notifications("?state=failed");
        assertThat(afterDeletion.get(0).get("ruleId")).as("rule_id is null, so non_null inclusion omits it").isNull();
        assertThat(afterDeletion.get(0).get("ruleName").asString()).isEqualTo(GONE);

        // FR-5.3's inbox mark: every entry a rule spoke about is labelled, whatever the verdict was; an entry no
        // rule mentioned is not.
        entry(feedId, "备份完成");
        JsonNode inbox = json.readTree(text(mvc.perform(csrfProtected(
            get("/api/entries?state=all&limit=50").session(session)))
            .andExpect(status().isOk())
            .andReturn()));        Map<String, JsonNode> byTitle = new HashMap<>();
        inbox.get("items").valueStream().forEach(item -> byTitle.put(item.get("title").asString(), item));
        assertThat(byTitle.get("OOM on node-3").get("alertedBy").valueStream()
            .map(JsonNode::asString).toList()).containsExactly(GONE);
        assertThat(byTitle.get("disk capacity warning").get("alertedBy").valueStream()
            .map(JsonNode::asString).toList()).containsExactly(GONE);
        assertThat(byTitle.get("备份完成").get("alertedBy").valueStream().toList()).isEmpty();
    }

    /** The delivery log as the UI reads it, filtered by whatever the page's controls put in the query string. */
    private JsonNode notifications(String query) throws Exception {
        return json.readTree(text(mvc.perform(csrfProtected(get("/api/notifications" + query).session(session)))
            .andExpect(status().isOk())
            .andReturn()));
    }

    @Test
    @DisplayName("FR-5.3: replay is offered only for a failed row, and only when an alert body survives")
    void replayIsTheOneWriteTheDeliveryLogAccepts() throws Exception {
        long feedId = feed();
        long entryId = entry(feedId, "OOM on node-3");
        long ruleId = createRule("重发", Map.of("includeAny", List.of("OOM")));

        long sentId = notification(ruleId, entryId, "webhook", "sent", 200, null, 1);
        mvc.perform(write(post("/api/notifications/" + sentId + "/replay")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:not_replayable"));
        // A suppressed hit never queued a body, so there is nothing to re-send even in principle.
        long suppressedId = notification(ruleId, entryId, "webhook", "suppressed", null, null, 0);
        mvc.perform(write(post("/api/notifications/" + suppressedId + "/replay")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:not_replayable"));
        mvc.perform(write(post("/api/notifications/999999/replay")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_notification"));

        long failedId = notification(ruleId, entryId, "webhook", "failed", 500, "HTTP 500", 5);
        mvc.perform(write(post("/api/notifications/" + failedId + "/replay")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:no_payload"));

        task(failedId, "failed", "{\"notificationId\":" + failedId + ",\"ruleName\":\"重发\"}");
        mvc.perform(write(post("/api/notifications/" + failedId + "/replay")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("pending"))
            .andExpect(jsonPath("$.attempts").value(0))
            .andExpect(jsonPath("$.error").doesNotExist())
            .andExpect(jsonPath("$.ruleName").value("重发"));
        // The row is queued again with the body that was frozen at hit time, not a fresh render of the rule.
        assertThat(db.sql("select state from scheduled_task where kind = 'deliver' and dedupe_key = ?")
            .param("deliver:" + failedId).query(String.class).single()).isEqualTo("pending");
        assertThat(db.sql("select payload from scheduled_task where kind = 'deliver' and dedupe_key = ?")
            .param("deliver:" + failedId).query(String.class).single()).contains("ruleName");

        // A second press while it is pending is the mistake the UI has to refuse.
        mvc.perform(write(post("/api/notifications/" + failedId + "/replay")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:not_replayable"));
    }

    @Test
    @DisplayName("FR-5.3: the health page counts the rules the breaker stopped, and only those")
    void theHealthPageShowsABreakerTrip() throws Exception {
        long stopped = createRule("熔断", Map.of("includeAny", List.of("OOM")));
        long paused = createRule("停用", Map.of("includeAny", List.of("panic")));
        db.sql("update rule set enabled = FALSE, disabled_reason = ? where id = ?")
            .params("delivery failed: task 4: connect timed out", stopped).update();
        db.sql("update rule set enabled = FALSE, disabled_reason = ? where id = ?")
            .params("操作员手动停用", paused).update();

        mvc.perform(csrfProtected(get("/api/health/feeds").session(session)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rulesDisabled").value(1))
            .andExpect(jsonPath("$.summary.total").value(0));
    }

    /** A rule saved through the API, so the ids the assertions use are the ones the editor would have got. */
    private long createRule(String name, Map<String, Object> match) throws Exception {
        return json.readTree(text(mvc.perform(write(post("/api/rules"), Map.of("name", name,
            "match", match,
            "actions", List.of(Map.of("channel", "webhook", "target", HOOK)))))
            .andExpect(status().isCreated())
            .andReturn())).get("id").asLong();
    }

    /** A POST whose only problem is the action, so the channel checks can be addressed one at a time. */
    private ResultActions writeAction(Map<String, Object> action) throws Exception {
        return mvc.perform(write(post("/api/rules"), Map.of("name", "通道",
            "match", Map.of("includeAny", List.of("OOM")),
            "actions", List.of(action))));
    }

    private void regex(String pattern) throws Exception {
        mvc.perform(write(post("/api/rules"), Map.of("name", "正则",
            "match", Map.of("regex", List.of(pattern)),
            "actions", List.of(Map.of("channel", "markOnly")))))
            .andExpect(status().isBadRequest());
    }

    private long feed() {
        db.sql("insert into feed (canonical_url, requested_url, title, group_name) values (?, ?, ?, ?)")
            .params("http://example.test/feed", "http://example.test/feed", "Loopback Weekly", "生产")
            .update();
        return db.sql("select id from feed").query(Long.class).single();
    }

    private long entry(long feedId, String title) {
        db.sql("insert into entry (feed_id, guid, title, url, summary) values (?, ?, ?, ?, ?)")
            .params(feedId, "guid-" + title, title, "http://example.test/" + feedId, "a summary")
            .update();
        return db.sql("select id from entry where guid = ?").param("guid-" + title).query(Long.class).single();
    }

    /**
     * A verdict written directly, because producing a {@code failed} row through the notifier means running a whole
     * five-attempt ladder — which is what {@code RuleDeliveryTest} does, against a receiver, for exactly that reason.
     */
    private long notification(long ruleId, long entryId, String channel, String state, Integer httpStatus,
        String error, int attempts) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        db.sql("insert into notification_log (rule_id, entry_id, channel, dedupe_key, state, http_status, error,"
                + " attempts, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)")
            .params(ruleId, entryId, channel, "rule:" + ruleId + ":perFeed:feed:" + entryId, state, httpStatus,
                error, attempts, JdbcTimes.toSql(Instant.now()))
            .update(key, "id");
        return key.getKey().longValue();
    }

    /** The queue row a delivery would have left behind, in the state the ladder ends it in. */
    private void task(long notificationId, String state, String payload) {
        db.sql("insert into scheduled_task (kind, ref_id, dedupe_key, state, fire_at, attempts, payload) "
                + "values ('deliver', ?, ?, ?, ?, 5, ?)")
            .params(notificationId, "deliver:" + notificationId, state, JdbcTimes.toSql(Instant.now()), payload)
            .update();
    }

    private MockHttpServletRequestBuilder loginInto(MockHttpSession target) {
        Cookie csrf = csrfCookie();
        return post("/api/auth/login").session(target)
            .param("username", "admin").param("password", PASSWORD)
            .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, Object body) {
        Cookie csrf = csrfCookie();
        return request.session(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body))
            .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
    }

    /** The same request when it carries no body: the test button and the replay button. */
    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return csrfProtected(request.session(session));
    }

    private MockHttpServletRequestBuilder csrfProtected(MockHttpServletRequestBuilder request) {
        Cookie csrf = csrfCookie();
        return request.cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
    }

    private Cookie csrfCookie() {
        try {
            Cookie cookie = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
            assertThat(cookie).as("XSRF-TOKEN must be readable by the browser that echoes it").isNotNull();
            return cookie;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String text(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
