package com.rsshub.adminapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rsshub.common.JdbcTimes;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * FR-8 over HTTP: the wall screen's one payload. The card is a projection, so the assertions here are about shape
 * and ordering — every feed visible, the newest first, the unread badge agreeing with the inbox's arithmetic.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:dashboardapi;DB_CLOSE_DELAY=-1",
    "rsshub.operator.username=admin",
    "rsshub.operator.password=a-test-only-password",
    "rsshub.fetch.enabled=false",
    "rsshub.notify.enabled=false"
})
@AutoConfigureMockMvc
class DashboardApiTest {

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
        db.sql("delete from settings").update();
        db.sql("delete from notification_log").update();
        db.sql("delete from scheduled_task").update();
        db.sql("delete from content_change").update();
        db.sql("delete from post").update();
        db.sql("delete from rule").update();
        db.sql("delete from entry").update();
        db.sql("delete from feed").update();

        session = new MockHttpSession();
        mvc.perform(loginInto(session)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("FR-8: an empty install is an empty wall, and the endpoint is login-only")
    void emptyDatabaseAndAuth() throws Exception {
        mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(csrfProtected(get("/api/dashboard").session(session)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entriesPerFeed").value(20))
            .andExpect(jsonPath("$.refreshSeconds").value(60))
            .andExpect(jsonPath("$.generatedAt").isNotEmpty())
            .andExpect(jsonPath("$.lastFetchedAt").doesNotExist())
            .andExpect(jsonPath("$.feeds.length()").value(0))
            .andExpect(jsonPath("$.stats.feeds").value(0))
            .andExpect(jsonPath("$.stats.unread").value(0))
            .andExpect(jsonPath("$.stats.arrivedToday").value(0))
            .andExpect(jsonPath("$.hourly.length()").value(24))
            .andExpect(jsonPath("$.hourly[0].arrivals").value(0))
            .andExpect(jsonPath("$.groups.length()").value(0))
            .andExpect(jsonPath("$.alerts.length()").value(0))
            .andExpect(jsonPath("$.posts.length()").value(0))
            .andExpect(jsonPath("$.changes.length()").value(0));
    }

    @Test
    @DisplayName("FR-8: one card per feed, grouped order, newest entries first inside each card")
    void oneCardPerFeedNewestFirst() throws Exception {
        long grouped = feed("http://example.test/a", "生产", "A 源", "active");
        long bare = feed("http://example.test/b", null, "B 源", "active");
        entry(grouped, "最早", Instant.now().minusSeconds(300));
        entry(grouped, "居中", Instant.now().minusSeconds(200));
        entry(grouped, "最新", Instant.now().minusSeconds(100));
        entry(bare, "B 的唯一", Instant.now().minusSeconds(50));

        mvc.perform(csrfProtected(get("/api/dashboard").session(session)))
            .andExpect(status().isOk())
            // Group ordering is the card list's contract: ungrouped feeds first, groups after, both alphabetical.
            .andExpect(jsonPath("$.feeds.length()").value(2))
            .andExpect(jsonPath("$.feeds[0].title").value("B 源"))
            .andExpect(jsonPath("$.feeds[0].groupName").doesNotExist())
            .andExpect(jsonPath("$.feeds[0].entries.length()").value(1))
            .andExpect(jsonPath("$.feeds[0].entries[0].title").value("B 的唯一"))
            .andExpect(jsonPath("$.feeds[1].title").value("A 源"))
            .andExpect(jsonPath("$.feeds[1].groupName").value("生产"))
            .andExpect(jsonPath("$.feeds[1].entries.length()").value(3))
            .andExpect(jsonPath("$.feeds[1].entries[0].title").value("最新"))
            .andExpect(jsonPath("$.feeds[1].entries[2].title").value("最早"))
            .andExpect(jsonPath("$.lastFetchedAt").isNotEmpty());
    }

    @Test
    @DisplayName("FR-8: the unread badge uses the inbox's arithmetic — read and baseline rows do not count")
    void unreadBadgeExcludesReadAndBaseline() throws Exception {
        long feedId = feed("http://example.test/badge", null, "计数源", "active");
        entry(feedId, "未读", Instant.now().minusSeconds(300));
        long read = entry(feedId, "已读", Instant.now().minusSeconds(200));
        db.sql("update entry set read_state = 'read' where id = ?").param(read).update();
        long baseline = entry(feedId, "存量", Instant.now().minusSeconds(100));
        db.sql("update entry set baseline = TRUE where id = ?").param(baseline).update();

        mvc.perform(csrfProtected(get("/api/dashboard").session(session)))
            .andExpect(jsonPath("$.feeds[0].unread").value(1))
            // The baseline row is still one of the feed's latest entries; the wall shows content, not just news.
            .andExpect(jsonPath("$.feeds[0].entries.length()").value(3))
            .andExpect(jsonPath("$.feeds[0].entries[1].readState").value("read"));
    }

    @Test
    @DisplayName("FR-8: dashboard.entriesPerFeed is honoured — the card keeps only the newest N")
    void honoursTheEntriesPerFeedSetting() throws Exception {
        long feedId = feed("http://example.test/n", null, "截断源", "active");
        entry(feedId, "第一条", Instant.now().minusSeconds(400));
        entry(feedId, "第二条", Instant.now().minusSeconds(300));
        entry(feedId, "第三条", Instant.now().minusSeconds(200));
        entry(feedId, "第四条", Instant.now().minusSeconds(100));
        mvc.perform(write(patch("/api/settings"), Map.of("dashboard.entriesPerFeed", 2)))
            .andExpect(status().isOk());

        mvc.perform(csrfProtected(get("/api/dashboard").session(session)))
            .andExpect(jsonPath("$.entriesPerFeed").value(2))
            .andExpect(jsonPath("$.feeds[0].entries.length()").value(2))
            .andExpect(jsonPath("$.feeds[0].entries[0].title").value("第四条"))
            .andExpect(jsonPath("$.feeds[0].entries[1].title").value("第三条"));
    }

    @Test
    @DisplayName("FR-8: a paused feed stays on the wall dimmed, and a feed without entries keeps its empty card")
    void pausedAndEmptyFeedsStayVisible() throws Exception {
        feed("http://example.test/paused", null, "暂停源", "paused");
        long quiet = feed("http://example.test/quiet", null, "安静源", "active");
        entry(quiet, "唯一旧文", Instant.now().minusSeconds(86400));

        mvc.perform(csrfProtected(get("/api/dashboard").session(session)))
            .andExpect(jsonPath("$.feeds.length()").value(2))
            .andExpect(jsonPath("$.feeds[0].title").value("安静源"))
            .andExpect(jsonPath("$.feeds[0].entries.length()").value(1))
            .andExpect(jsonPath("$.feeds[1].title").value("暂停源"))
            .andExpect(jsonPath("$.feeds[1].state").value("paused"))
            .andExpect(jsonPath("$.feeds[1].entries.length()").value(0))
            .andExpect(jsonPath("$.feeds[1].unread").value(0));
    }

    @Test
    @DisplayName("FR-8: the window's totals and the histogram are literally the same rows")
    void totalsAndHistogramAgree() throws Exception {
        long feedId = feed("http://example.test/win", null, "窗口源", "active");
        db.sql("update feed set last_fetched_at = ? where id = ?")
            .params(JdbcTimes.toSql(Instant.now()), feedId).update();
        entry(feedId, "刚来", Instant.now());
        entry(feedId, "今早", Instant.now().minusSeconds(60));
        entry(feedId, "今早二", Instant.now().minusSeconds(30));
        stamp(entry(feedId, "前天到的", Instant.now().minusSeconds(300)), Instant.now().minus(Duration.ofHours(40)));

        long ruleId = rule("窗口规则");
        alert(ruleId, "sent", "webhook", Instant.now());
        alert(ruleId, "sent", "email", Instant.now());
        alert(ruleId, "failed", "webhook", Instant.now());
        alert(ruleId, "suppressed", "webhook", Instant.now());
        alert(ruleId, "sent", "webhook", Instant.now().minus(Duration.ofHours(40)));

        JsonNode wall = wall();
        assertThat(wall.path("stats").path("arrivedToday").asLong()).isEqualTo(3);
        assertThat(wall.path("stats").path("entries").asLong()).isEqualTo(4);
        assertThat(wall.path("stats").path("hitsToday").asLong()).isEqualTo(4);
        assertThat(wall.path("stats").path("deliveredToday").asLong()).isEqualTo(2);
        assertThat(wall.path("stats").path("failedToday").asLong()).isEqualTo(1);
        assertThat(wall.path("stats").path("problemFeeds").asLong()).isZero();
        assertThat(wall.path("stats").path("quietFeeds").asLong()).isZero();

        // The bars and the headline number are read off the same window, so they cannot drift apart.
        assertThat(wall.path("hourly").size()).isEqualTo(24);
        long arrivals = 0;
        long hits = 0;
        for (JsonNode bucket : wall.path("hourly")) {
            arrivals += bucket.path("arrivals").asLong();
            hits += bucket.path("hits").asLong();
        }
        assertThat(arrivals).isEqualTo(wall.path("stats").path("arrivedToday").asLong());
        assertThat(hits).isEqualTo(wall.path("stats").path("hitsToday").asLong());
    }

    @Test
    @DisplayName("FR-8: the group rail folds the cards, and the unfiled pile is its own bucket")
    void groupRailFoldsTheCards() throws Exception {
        long first = feed("http://example.test/g1", "生产", "甲源", "active");
        long second = feed("http://example.test/g2", "生产", "乙源", "active");
        feed("http://example.test/g3", "测试", "丙源", "active");
        feed("http://example.test/g4", null, "丁源", "active");
        entry(first, "甲一", Instant.now());
        entry(first, "甲二", Instant.now());
        entry(second, "乙一", Instant.now());

        JsonNode groups = wall().path("groups");
        // Busiest folder first, then by name — and the empty name is the pile nobody filed, not a missing group.
        assertThat(groups.get(0).path("name").asString()).isEqualTo("生产");
        assertThat(groups.get(0).path("feeds").asLong()).isEqualTo(2);
        assertThat(groups.get(0).path("unread").asLong()).isEqualTo(3);
        assertThat(groups.get(0).path("arrivedToday").asLong()).isEqualTo(3);
        assertThat(groups.get(1).path("name").asString()).isEmpty();
        assertThat(groups.get(1).path("feeds").asLong()).isEqualTo(1);
        assertThat(groups.get(1).path("arrivedToday").asLong()).isZero();
        assertThat(groups.get(2).path("name").asString()).isEqualTo("测试");
        assertThat(groups.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("FR-8: the alert rail names the rule, and outlives both the rule and the entry it was about")
    void alertRailSurvivesDeletions() throws Exception {
        long feedId = feed("http://example.test/alert", null, "告警源", "active");
        long kept = entry(feedId, "留下的条目", Instant.now());
        long doomed = entry(feedId, "将被删除的条目", Instant.now());
        long ruleId = rule("在线规则");
        alertOf(ruleId, kept, "sent");
        long doomedAlert = alertOf(ruleId, doomed, "failed");

        JsonNode wall = wall();
        assertThat(wall.path("alerts").get(0).path("id").asLong()).isEqualTo(doomedAlert);
        assertThat(wall.path("alerts").get(0).path("ruleName").asString()).isEqualTo("在线规则");
        assertThat(wall.path("alerts").get(0).path("state").asString()).isEqualTo("failed");
        assertThat(wall.path("alerts").get(0).path("entryTitle").asString()).isEqualTo("将被删除的条目");
        assertThat(wall.path("alerts").get(1).path("entryTitle").asString()).isEqualTo("留下的条目");

        // Both foreign keys are ON DELETE SET NULL: the hit is the fact, and the fact stays on the wall.
        db.sql("delete from entry where id = ?").param(doomed).update();
        db.sql("delete from rule where id = ?").param(ruleId).update();
        JsonNode after = wall();
        assertThat(after.path("alerts").get(0).path("ruleName").asString()).isEqualTo("（规则已删除）");
        assertThat(after.path("alerts").get(0).has("entryTitle")).isFalse();
        assertThat(after.path("alerts").get(1).path("ruleName").asString()).isEqualTo("（规则已删除）");
        assertThat(after.path("stats").path("hitsToday").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("FR-8: the publish rail follows the outlets' visibility, drafts and the not-yet-due included")
    void postsRailFollowsVisibility() throws Exception {
        publish("published-one", "已发布", "published", Instant.now().minusSeconds(60));
        publish("scheduled-past", "已到点", "scheduled", Instant.now().minusSeconds(120));
        publish("scheduled-future", "还没到", "scheduled", Instant.now().plusSeconds(3_600));
        publish("draft-one", "草稿", "draft", null);
        publish("hidden-one", "不公开", "unlisted", Instant.now().minusSeconds(90));

        JsonNode wall = wall();
        assertThat(wall.path("posts").size()).isEqualTo(2);
        assertThat(wall.path("posts").get(0).path("slug").asString()).isEqualTo("published-one");
        assertThat(wall.path("posts").get(1).path("slug").asString()).isEqualTo("scheduled-past");
        assertThat(wall.path("stats").path("publishedPosts").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("FR-8: the changelog rail is newest first with the action that produced the row")
    void changesRailNewestFirst() throws Exception {
        change("第一篇", "http://example.test/blog/one", "created");
        change("第一篇改名", "http://example.test/blog/one", "updated");

        JsonNode changes = wall().path("changes");
        assertThat(changes.size()).isEqualTo(2);
        assertThat(changes.get(0).path("title").asString()).isEqualTo("第一篇改名");
        assertThat(changes.get(0).path("action").asString()).isEqualTo("updated");
        assertThat(changes.get(0).path("url").asString()).isEqualTo("http://example.test/blog/one");
        assertThat(changes.get(1).path("action").asString()).isEqualTo("created");
    }

    @Test
    @DisplayName("FR-8: a card carries its failures and the next time the queue will run it")
    void cardCarriesFailuresAndQueue() throws Exception {
        long failing = feed("http://example.test/fail", null, "故障源", "error");
        db.sql("update feed set fail_count = 3, last_error = '连接超时', last_fetched_at = ?, last_change_at = ? "
                + "where id = ?")
            .params(JdbcTimes.toSql(Instant.now().minusSeconds(30)),
                JdbcTimes.toSql(Instant.now().minus(Duration.ofDays(4))), failing)
            .update();
        arm(failing, Instant.now().plus(Duration.ofHours(1)), "pending");
        long idle = feed("http://example.test/idle", null, "空闲源", "active");
        arm(idle, Instant.now().minus(Duration.ofHours(5)), "success");

        JsonNode wall = wall();
        JsonNode broken = card(wall, "故障源");
        assertThat(broken.path("state").asString()).isEqualTo("error");
        assertThat(broken.path("failCount").asInt()).isEqualTo(3);
        assertThat(broken.path("lastError").asString()).isEqualTo("连接超时");
        assertThat(broken.path("lastFetchedAt").isMissingNode()).isFalse();
        assertThat(broken.path("lastChangeAt").isMissingNode()).isFalse();
        // The queue's next fire is what the card promises; a finished task is not a promise about the future.
        assertThat(broken.path("nextFetchAt").isMissingNode()).isFalse();
        assertThat(card(wall, "空闲源").has("nextFetchAt")).isFalse();
        assertThat(wall.path("stats").path("problemFeeds").asLong()).isEqualTo(1);
        assertThat(wall.path("stats").path("quietFeeds").asLong()).isEqualTo(1);
    }

    private JsonNode wall() throws Exception {
        String body = mvc.perform(csrfProtected(get("/api/dashboard").session(session)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static JsonNode card(JsonNode wall, String title) {
        for (JsonNode feed : wall.path("feeds")) {
            if (title.equals(feed.path("title").asString())) {
                return feed;
            }
        }
        throw new AssertionError("no card titled " + title);
    }

    private long feed(String canonicalUrl, String groupName, String title, String state) {
        db.sql("insert into feed (canonical_url, requested_url, title, group_name, state) values (?, ?, ?, ?, ?)")
            .params(canonicalUrl, canonicalUrl, title, groupName, state)
            .update();
        return db.sql("select id from feed where canonical_url = ?").param(canonicalUrl)
            .query(Long.class).single();
    }

    private long entry(long feedId, String title, Instant publishedAt) {
        db.sql("insert into entry (feed_id, guid, title, url, published_at) values (?, ?, ?, ?, ?)")
            .params(feedId, "guid-" + title, title, "http://example.test/" + title, JdbcTimes.toSql(publishedAt))
            .update();
        return db.sql("select id from entry where guid = ?").param("guid-" + title).query(Long.class).single();
    }

    /** Move a row's arrival out of the wall's window without touching anything else about it. */
    private void stamp(long entryId, Instant fetchedAt) {
        db.sql("update entry set fetched_at = ? where id = ?").params(JdbcTimes.toSql(fetchedAt), entryId).update();
    }

    private long rule(String name) {
        db.sql("insert into rule (name, match, actions) values (?, '{\"includeAny\":[\"oom\"]}', "
                + "'[{\"channel\":\"markOnly\"}]')").param(name).update();
        return db.sql("select id from rule where name = ?").param(name).query(Long.class).single();
    }

    /** A hit with no entry of its own, for the window arithmetic. */
    private void alert(long ruleId, String state, String channel, Instant createdAt) {
        db.sql("insert into notification_log (rule_id, channel, dedupe_key, state, created_at) "
                + "values (?, ?, ?, ?, ?)")
            .params(ruleId, channel, "rule:" + ruleId + ":" + channel + ":" + state + ":" + createdAt.toEpochMilli(),
                state, JdbcTimes.toSql(createdAt))
            .update();
    }

    /** A hit about a specific entry, which is what the rail renders. */
    private long alertOf(long ruleId, long entryId, String state) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        db.sql("insert into notification_log (rule_id, entry_id, channel, dedupe_key, state, created_at) "
                + "values (?, ?, 'webhook', ?, ?, ?)")
            .params(ruleId, entryId, "rule:" + ruleId + ":entry:" + entryId, state,
                JdbcTimes.toSql(Instant.now()))
            .update(key, "id");
        return key.getKey().longValue();
    }

    /**
     * A row in the publisher's own table. Named {@code publish} rather than {@code post} because the latter would
     * shadow {@code MockMvcRequestBuilders.post}, which this class uses for the login and settings requests.
     */
    private void publish(String slug, String title, String status, Instant publishedAt) {
        db.sql("insert into post (slug, title, body_md, body_html, status, published_at) values (?, ?, ?, ?, ?, ?)")
            .params(slug, title, "正文", "<p>正文</p>", status,
                publishedAt == null ? null : JdbcTimes.toSql(publishedAt))
            .update();
    }

    private void change(String title, String url, String action) {
        db.sql("insert into content_change (post_id, post_slug, post_title, post_url, action) "
                + "values (?, 'one', ?, ?, ?)").params(1L, title, url, action).update();
    }

    private void arm(long feedId, Instant fireAt, String state) {
        db.sql("insert into scheduled_task (kind, ref_id, dedupe_key, state, fire_at) values ('fetch', ?, ?, ?, ?)")
            .params(feedId, "fetch:" + feedId + ":" + state, state, JdbcTimes.toSql(fireAt)).update();
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
}
