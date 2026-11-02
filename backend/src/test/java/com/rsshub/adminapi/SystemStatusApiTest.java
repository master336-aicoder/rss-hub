package com.rsshub.adminapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rsshub.common.JdbcTimes;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * FR-9's status half over HTTP. The fields are JDK and SQL facts, so the tests assert presence and sanity rather
 * than exact numbers — but the two booleans are load-bearing: they must read the very properties the dispatchers'
 * {@code @ConditionalOnProperty} reads, or the page would show services the process never started.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:sysapi;DB_CLOSE_DELAY=-1",
    "rsshub.operator.username=admin",
    "rsshub.operator.password=a-test-only-password",
    "rsshub.fetch.enabled=false",
    "rsshub.notify.enabled=false"
})
@AutoConfigureMockMvc
class SystemStatusApiTest {

    private static final String PASSWORD = "a-test-only-password";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient db;

    @Autowired
    ObjectMapper json;

    private MockHttpSession session;

    @BeforeEach
    void startEmpty() throws Exception {
        db.sql("delete from notification_log").update();
        db.sql("delete from scheduled_task").update();
        db.sql("delete from rule").update();
        db.sql("delete from entry").update();
        db.sql("delete from feed").update();

        session = new MockHttpSession();
        mvc.perform(loginInto(session)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("FR-9: the endpoint is login-only and reports system, JVM and service facts")
    void reportsTheMachineAndItsServices() throws Exception {
        mvc.perform(get("/api/system/status")).andExpect(status().isUnauthorized());
        mvc.perform(csrfProtected(get("/api/system/status").session(session)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.now").isNotEmpty())
            .andExpect(jsonPath("$.system.osName").isNotEmpty())
            .andExpect(jsonPath("$.system.osArch").isNotEmpty())
            .andExpect(jsonPath("$.system.javaVersion").isNotEmpty())
            .andExpect(jsonPath("$.system.jvmStartedAt").isNotEmpty())
            .andExpect(jsonPath("$.system.uptimeSeconds").isNumber())
            .andExpect(jsonPath("$.jvm.heapMaxBytes").isNumber())
            .andExpect(jsonPath("$.jvm.liveThreads").isNumber())
            .andExpect(jsonPath("$.jvm.loadedClasses").isNumber())
            // Both dispatchers are switched off by this test context's properties, and the page must agree.
            .andExpect(jsonPath("$.services.fetchEnabled").value(false))
            .andExpect(jsonPath("$.services.notifyEnabled").value(false))
            .andExpect(jsonPath("$.database.up").value(true))
            .andExpect(jsonPath("$.database.productName").value(containsString("H2")));
    }

    @Test
    @DisplayName("FR-9: the database counts and both state breakdowns match what is actually stored")
    void countsMatchTheStoredRows() throws Exception {
        long first = feed("http://example.test/one", "一号源");
        long second = feed("http://example.test/two", "二号源");
        for (int i = 1; i <= 4; i++) {
            entry(first, "第 " + i + " 条", Instant.now().minusSeconds(600 - i));
        }
        entry(second, "跨源条目", Instant.now().minusSeconds(60));
        long ruleId = rule();
        notification(ruleId, "pending");
        notification(ruleId, "sent");
        db.sql("insert into scheduled_task (kind, ref_id, dedupe_key, state, fire_at) "
                + "values ('fetch', ?, ?, 'pending', ?)")
            .params(first, "fetch:feed:" + first, JdbcTimes.toSql(Instant.now().plusSeconds(60)))
            .update();

        mvc.perform(csrfProtected(get("/api/system/status").session(session)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.database.feeds").value(2))
            .andExpect(jsonPath("$.database.entries").value(5))
            .andExpect(jsonPath("$.database.rules").value(1))
            .andExpect(jsonPath("$.database.notifications").value(2))
            .andExpect(jsonPath("$.services.taskQueueByState.pending").value(1))
            .andExpect(jsonPath("$.services.notificationsByState.pending").value(1))
            .andExpect(jsonPath("$.services.notificationsByState.sent").value(1))
            .andExpect(jsonPath("$.services.lastFetchedAt").isNotEmpty());
    }

    @Test
    @DisplayName("FR-9: with nothing stored the counts are zeros, not errors or absent fields")
    void zeroRowsIsAZeroStatusNotAnError() throws Exception {
        mvc.perform(csrfProtected(get("/api/system/status").session(session)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.database.feeds").value(0))
            .andExpect(jsonPath("$.database.entries").value(0))
            .andExpect(jsonPath("$.database.rules").value(0))
            .andExpect(jsonPath("$.database.notifications").value(0))
            .andExpect(jsonPath("$.services.lastFetchedAt").doesNotExist());
    }

    private long feed(String canonicalUrl, String title) {
        db.sql("insert into feed (canonical_url, requested_url, title) values (?, ?, ?)")
            .params(canonicalUrl, canonicalUrl, title)
            .update();
        return db.sql("select id from feed where canonical_url = ?").param(canonicalUrl)
            .query(Long.class).single();
    }

    private void entry(long feedId, String title, Instant publishedAt) {
        db.sql("insert into entry (feed_id, guid, title, url, published_at) values (?, ?, ?, ?, ?)")
            .params(feedId, "guid-" + title, title, "http://example.test/" + title, JdbcTimes.toSql(publishedAt))
            .update();
    }

    private long rule() throws Exception {
        String body = text(mvc.perform(write(post("/api/rules"), Map.of(
            "name", "状态页规则",
            "match", Map.of("includeAny", List.of("关键词")),
            "actions", List.of(Map.of("channel", "markOnly")))))
            .andExpect(status().isCreated()).andReturn());
        return json.readTree(body).get("id").asLong();
    }

    private void notification(long ruleId, String state) {
        db.sql("insert into notification_log (rule_id, channel, dedupe_key, state, attempts, created_at) "
                + "values (?, 'markOnly', ?, ?, 0, ?)")
            .params(ruleId, "rule:" + ruleId + ":feed:0", state, JdbcTimes.toSql(Instant.now()))
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
