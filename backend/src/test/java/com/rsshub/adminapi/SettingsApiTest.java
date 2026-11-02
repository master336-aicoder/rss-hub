package com.rsshub.adminapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * FR-9's knobs over HTTP. The point these tests defend is not the four numbers — it is that the page cannot accept
 * a value production code would ignore: every key here is read back through the endpoints that actually use it.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:settingsapi;DB_CLOSE_DELAY=-1",
    "rsshub.operator.username=admin",
    "rsshub.operator.password=a-test-only-password",
    "rsshub.fetch.enabled=false",
    "rsshub.notify.enabled=false"
})
@AutoConfigureMockMvc
class SettingsApiTest {

    private static final String PASSWORD = "a-test-only-password";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient db;

    @Autowired
    ObjectMapper json;

    private MockHttpSession session;

    @BeforeEach
    void startFromDefaults() throws Exception {
        db.sql("delete from settings").update();
        db.sql("delete from notification_log").update();
        db.sql("delete from scheduled_task").update();
        db.sql("delete from rule").update();
        db.sql("delete from entry").update();
        db.sql("delete from feed").update();

        session = new MockHttpSession();
        mvc.perform(loginInto(session)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("FR-9: a fresh install serves the four defaults, and the page is login-only")
    void defaultsAndAuth() throws Exception {
        mvc.perform(get("/api/settings")).andExpect(status().isUnauthorized());
        mvc.perform(csrfProtected(get("/api/settings").session(session)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dashboardEntriesPerFeed").value(20))
            .andExpect(jsonPath("$.dashboardRefreshSeconds").value(60))
            .andExpect(jsonPath("$.inboxPageSize").value(50))
            .andExpect(jsonPath("$.notificationsPageSize").value(100));
    }

    @Test
    @DisplayName("FR-9: a PATCH stores only the keys it was sent, so neighbours keep their values")
    void patchIsPartialAndPersistent() throws Exception {
        mvc.perform(write(patch("/api/settings"), Map.of("dashboard.entriesPerFeed", 10)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dashboardEntriesPerFeed").value(10))
            .andExpect(jsonPath("$.dashboardRefreshSeconds").value(60))
            .andExpect(jsonPath("$.inboxPageSize").value(50));

        mvc.perform(write(patch("/api/settings"), Map.of("inbox.pageSize", 25)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dashboardEntriesPerFeed").value(10))
            .andExpect(jsonPath("$.inboxPageSize").value(25));

        mvc.perform(csrfProtected(get("/api/settings").session(session)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dashboardEntriesPerFeed").value(10))
            .andExpect(jsonPath("$.inboxPageSize").value(25));
        assertThat(db.sql("select count(*) from settings").query(Long.class).single())
            .as("two PATCHes, two rows — no rewrite of the whole view").isEqualTo(2L);
    }

    @Test
    @DisplayName("FR-9: an unknown key is refused, not silently dropped")
    void refusesUnknownKeys() throws Exception {
        mvc.perform(write(patch("/api/settings"), Map.of("fetch.intervalMinutes", 5)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_setting"));
        mvc.perform(csrfProtected(get("/api/settings").session(session)))
            .andExpect(jsonPath("$.inboxPageSize").value(50));
    }

    @Test
    @DisplayName("FR-9: only whole numbers pass — strings, floats and booleans are type guesses")
    void refusesNonWholeNumbers() throws Exception {
        for (Object bad : List.of("abc", 12.5, true)) {
            mvc.perform(write(patch("/api/settings"), Map.of("dashboard.refreshSeconds", bad)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:rsshub:bad_setting"));
        }
        mvc.perform(csrfProtected(get("/api/settings").session(session)))
            .andExpect(jsonPath("$.dashboardRefreshSeconds").value(60));
    }

    @Test
    @DisplayName("FR-9: out-of-range values are refused, and refused before anything is stored")
    void refusesOutOfRangeValues() throws Exception {
        for (Object[] bad : new Object[][] {
            {"dashboard.entriesPerFeed", 0}, {"dashboard.entriesPerFeed", 51},
            {"dashboard.refreshSeconds", -1}, {"inbox.pageSize", 9}, {"notifications.pageSize", 501}}) {
            mvc.perform(write(patch("/api/settings"), Map.of((String) bad[0], bad[1])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:rsshub:setting_out_of_range"));
        }
        assertThat(db.sql("select count(*) from settings").query(Long.class).single())
            .as("a rejected PATCH stores nothing").isZero();
    }

    @Test
    @DisplayName("FR-9: the range ends themselves are accepted — a max the controller clamps away would be a lie")
    void acceptsTheRangeEnds() throws Exception {
        mvc.perform(write(patch("/api/settings"), Map.of(
            "dashboard.entriesPerFeed", 1,
            "dashboard.refreshSeconds", 0,
            "inbox.pageSize", 200,
            "notifications.pageSize", 500)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dashboardEntriesPerFeed").value(1))
            .andExpect(jsonPath("$.dashboardRefreshSeconds").value(0))
            .andExpect(jsonPath("$.inboxPageSize").value(200))
            .andExpect(jsonPath("$.notificationsPageSize").value(500));
    }

    @Test
    @DisplayName("FR-9: inbox.pageSize is the list's real default, and an explicit limit still wins")
    void steersTheInboxDefault() throws Exception {
        long feed = feed("http://example.test/settings-feed");
        for (int i = 1; i <= 12; i++) {
            entry(feed, "第 " + i + " 条", Instant.now().minusSeconds(600 - i));
        }
        // The knob's floor is 10, so the seeded page is 12 rows against a default of 10.
        mvc.perform(write(patch("/api/settings"), Map.of("inbox.pageSize", 10)))
            .andExpect(status().isOk());

        mvc.perform(csrfProtected(get("/api/entries").session(session)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(10))
            .andExpect(jsonPath("$.nextCursor").isNotEmpty());

        mvc.perform(csrfProtected(get("/api/entries?limit=12").session(session)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(12))
            .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("FR-9: notifications.pageSize is the delivery log's real default")
    void steersTheNotificationsDefault() throws Exception {
        long feedId = feed("http://example.test/settings-feed");
        long entryId = entryReturningId(feedId, "命中条目", Instant.now().minusSeconds(300));
        long ruleId = rule();
        for (int i = 1; i <= 12; i++) {
            notification(ruleId, entryId, "markOnly");
        }
        mvc.perform(write(patch("/api/settings"), Map.of("notifications.pageSize", 10)))
            .andExpect(status().isOk());

        mvc.perform(csrfProtected(get("/api/notifications").session(session)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(10));

        mvc.perform(csrfProtected(get("/api/notifications?limit=12").session(session)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(12));
    }

    @Test
    @DisplayName("FR-9: a hand-edited row outside the range is clamped; an unparseable one falls back to default")
    void survivesHandEditedRows() throws Exception {
        db.sql("insert into settings (setting_key, setting_value, updated_at) values (?, ?, ?)")
            .params("dashboard.entriesPerFeed", "999", JdbcTimes.toSql(Instant.now()))
            .update();
        db.sql("insert into settings (setting_key, setting_value, updated_at) values (?, ?, ?)")
            .params("inbox.pageSize", "not-a-number", JdbcTimes.toSql(Instant.now()))
            .update();

        mvc.perform(csrfProtected(get("/api/settings").session(session)))
            .andExpect(jsonPath("$.dashboardEntriesPerFeed").value(50))
            .andExpect(jsonPath("$.inboxPageSize").value(50));
    }

    private long feed(String canonicalUrl) {
        db.sql("insert into feed (canonical_url, requested_url, title) values (?, ?, ?)")
            .params(canonicalUrl, canonicalUrl, "设置源")
            .update();
        return db.sql("select id from feed where canonical_url = ?").param(canonicalUrl)
            .query(Long.class).single();
    }

    private void entry(long feedId, String title, Instant publishedAt) {
        entryReturningId(feedId, title, publishedAt);
    }

    private long entryReturningId(long feedId, String title, Instant publishedAt) {
        db.sql("insert into entry (feed_id, guid, title, url, published_at) values (?, ?, ?, ?, ?)")
            .params(feedId, "guid-" + title, title, "http://example.test/" + title, JdbcTimes.toSql(publishedAt))
            .update();
        return db.sql("select id from entry where guid = ?").param("guid-" + title).query(Long.class).single();
    }

    private long rule() throws Exception {
        String body = text(mvc.perform(write(post("/api/rules"), Map.of(
            "name", "标记即可",
            "match", Map.of("includeAny", List.of("关键词")),
            "actions", List.of(Map.of("channel", "markOnly")))))
            .andExpect(status().isCreated()).andReturn());
        return json.readTree(body).get("id").asLong();
    }

    private void notification(long ruleId, long entryId, String channel) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        db.sql("insert into notification_log (rule_id, entry_id, channel, dedupe_key, state, attempts, created_at) "
                + "values (?, ?, ?, ?, 'sent', 0, ?)")
            .params(ruleId, entryId, channel, "rule:" + ruleId + ":feed:0", JdbcTimes.toSql(Instant.now()))
            .update(key, "id");
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
