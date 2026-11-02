package com.rsshub.changelog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rsshub.feedrender.FeedFormat;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * FR-1.6's acceptance gate: every write to the publishing side that a reader could tell about shows up once in
 * {@code /feed/changelog}, nothing a reader must not tell about shows up at all, and the outlet behaves the way the
 * other three formats do — anonymous, validated, and unchanged until the content actually moves.
 *
 * <p>The three cases that are easy to get wrong are all here on purpose: a draft's title leaking into a public
 * document, a retraction that would vanish with the row it retracts, and an editor that saves the same document twice
 * and announces it twice.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:changelogapi;DB_CLOSE_DELAY=-1",
    "rsshub.operator.username=admin",
    "rsshub.operator.password=a-test-only-password",
    "rsshub.fetch.enabled=false",
    "rsshub.publish.base-url=https://notes.example.org",
    "rsshub.publish.site-title=Observability Notes",
    "rsshub.publish.description=Notes about kernels, tracing and time series"
})
@AutoConfigureMockMvc
class ChangeLogApiTest {

    private static final String PASSWORD = "a-test-only-password";
    private static final String BASE = "https://notes.example.org";
    private static final String OUTLET = "/feed/changelog.";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient db;

    @Autowired
    ObjectMapper json;

    private MockHttpSession session;

    @BeforeEach
    void startFromAnEmptySite() throws Exception {
        db.sql("delete from content_change").update();
        db.sql("delete from post_tag").update();
        db.sql("delete from post_category").update();
        db.sql("delete from tag").update();
        db.sql("delete from category").update();
        db.sql("delete from post").update();
        db.sql("delete from event_item").update();
        db.sql("delete from event_stream").update();

        session = new MockHttpSession();
        Cookie csrf = csrf();
        mvc.perform(post("/api/auth/login").session(session)
                .param("username", "admin").param("password", PASSWORD)
                .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("TC-CHG-01/02/03: one publish is announced in all three formats, anonymously")
    void aPublishedPostIsAnnouncedThreeTimesOver() throws Exception {
        long id = create("The Verifier Rejects My Program", "body", "published");

        String rss = text(mvc.perform(get(OUTLET + "rss")));
        String atom = text(mvc.perform(get(OUTLET + "atom")));
        JsonNode document = json.readTree(text(mvc.perform(get(OUTLET + "json"))));

        String permalink = BASE + "/blog/the-verifier-rejects-my-program";
        assertThat(rss).contains("<title>New post: The Verifier Rejects My Program</title>")
            .contains("<link>" + permalink + "</link>")
            .contains("<atom:link href=\"" + BASE + "/feed/changelog.rss\" rel=\"self\"")
            .contains("<category>created</category>");
        assertThat(atom).contains("<link rel=\"self\" type=\"application/atom+xml\" href=\"" + BASE
                + "/feed/changelog.atom\"/>")
            .contains("<title>New post: The Verifier Rejects My Program</title>");
        assertThat(document.get("title").asString()).isEqualTo("Changelog · Observability Notes");
        assertThat(document.get("home_page_url").asString()).isEqualTo(BASE);
        assertThat(document.get("feed_url").asString()).isEqualTo(BASE + "/feed/changelog.json");
        JsonNode item = document.get("items").get(0);
        assertThat(item.get("url").asString()).isEqualTo(permalink);
        assertThat(item.get("tags").get(0).asString()).isEqualTo("created");
        // A change record is a pointer, never a second copy of the article.
        assertThat(item.has("content_html")).isFalse();

        assertThat(between(rss, "<guid isPermaLink=\"false\">", "</guid>")).startsWith("urn:rsshub:change:");
        assertThat(rowCount()).isEqualTo(1);
        assertThat(id).isPositive();
    }

    @Test
    @DisplayName("TC-CHG-04: a draft is not news, and its title must not reach a public document")
    void draftsAreWrittenSilently() throws Exception {
        long id = create("Unreleased Kernel Plans", "secret plan", "draft");
        mvc.perform(write(put("/api/posts/" + id), payload("Unreleased Kernel Plans", "edited secret", null)))
            .andExpect(status().isOk());

        assertThat(rowCount()).isZero();
        for (String format : List.of("rss", "atom", "json")) {
            assertThat(text(mvc.perform(get(OUTLET + format)))).doesNotContain("Unreleased Kernel Plans");
        }
        // The outlet exists and answers before there is anything to say, like the other three.
        mvc.perform(get(OUTLET + "rss")).andExpect(status().isOk());
        assertThat(json.readTree(text(mvc.perform(get(OUTLET + "json")))).get("items").size()).isZero();

        // Publishing it is the first thing the public learns, and the announcement carries the title it had as a draft.
        mvc.perform(write(patch("/api/posts/" + id + "/status"), Map.of("status", "published")))
            .andExpect(status().isOk());
        assertThat(text(mvc.perform(get(OUTLET + "rss")))).contains("Published: Unreleased Kernel Plans");
    }

    @Test
    @DisplayName("TC-CHG-05/06: a retraction outlives the row it retracts")
    void unpublishingAndDeletingBothAnnounceThemselves() throws Exception {
        long id = create("Retracted Study", "body", "published");
        String permalink = BASE + "/blog/retracted-study";
        assertThat(text(mvc.perform(get(OUTLET + "rss")))).contains("New post: Retracted Study");

        mvc.perform(write(patch("/api/posts/" + id + "/status"), Map.of("status", "draft")))
            .andExpect(status().isOk());
        assertThat(text(mvc.perform(get(OUTLET + "rss")))).contains("Back to draft: Retracted Study");

        mvc.perform(write(patch("/api/posts/" + id + "/status"), Map.of("status", "published")))
            .andExpect(status().isOk());
        mvc.perform(write(delete("/api/posts/" + id))).andExpect(status().isNoContent());

        // The post table has no row left to join to, so the outlet can only say what it was told at write time.
        assertThat(text(mvc.perform(get(OUTLET + "rss")))).contains("Deleted: Retracted Study").contains(permalink)
            .contains("no longer resolves");
        assertThat(text(mvc.perform(get("/feed.rss")))).doesNotContain(permalink);
        mvc.perform(get("/blog/retracted-study")).andExpect(status().isNotFound());
        assertThat(db.sql("select count(*) from post").query(Long.class).single()).isZero();
        assertThat(rowCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("TC-CHG-07/08: only a real change is announced, and a refused write announces nothing")
    void noiseAndRefusalsLeaveNoRows() throws Exception {
        long id = create("Quiet Edit", "first body", "published");
        assertThat(rows()).hasSize(1);

        // The same document PUT back is not a change, even though the row's updated_at moves.
        mvc.perform(write(put("/api/posts/" + id), payload("Quiet Edit", "first body", null)))
            .andExpect(status().isOk());
        mvc.perform(write(patch("/api/posts/" + id + "/status"), Map.of("status", "published")))
            .andExpect(status().isOk());
        assertThat(rows()).as("a no-op save and a same-status patch are silent").hasSize(1);

        mvc.perform(write(put("/api/posts/" + id), payload("Quiet Edit", "second body", null)))
            .andExpect(status().isOk());
        assertThat(rows()).hasSize(2);

        // published -> scheduled is not in the transition table: the 409 must not have written anything first.
        mvc.perform(write(patch("/api/posts/" + id + "/status"), Map.of("status", "scheduled")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("urn:rsshub:illegal_transition"));
        mvc.perform(write(post("/api/posts"), payload("Title only", null, null))).andExpect(status().isBadRequest());
        assertThat(rows()).hasSize(2);
    }

    @Test
    @DisplayName("TC-CHG-09: an announcement keeps its own id when the article it names is renamed")
    void aChangeRowHasAStableIdOfItsOwn() throws Exception {
        long id = create("Old Title", "body", "published");
        String first = json.readTree(text(mvc.perform(get(OUTLET + "json")))).get("items").get(0).get("id").asString();

        mvc.perform(write(put("/api/posts/" + id), payload("Renamed", "body changed", null)))
            .andExpect(status().isOk());

        JsonNode items = json.readTree(text(mvc.perform(get(OUTLET + "json")))).get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(1).get("id").asString()).as("a later edit does not rewrite the older entry")
            .isEqualTo(first);
        assertThat(items.get(0).get("title").asString()).isEqualTo("Updated: Renamed");
        assertThat(items.get(1).get("title").asString()).isEqualTo("New post: Old Title");
        // Two entries about one article, so the changelog id cannot be the permalink the post already uses.
        assertThat(items.get(0).get("id").asString()).isNotEqualTo(items.get(1).get("id").asString())
            .doesNotContain("/blog/");
        assertThat(items.get(0).get("url").asString()).isEqualTo(BASE + "/blog/old-title");
        assertThat(items.get(1).get("url").asString()).isEqualTo(BASE + "/blog/old-title");
    }

    @Test
    @DisplayName("TC-CHG-10/11/12: the outlet is a polling target like the others, and it does not remember subscribers")
    void theChangelogAnswersWithRealValidators() throws Exception {
        create("First", "b", "published");
        MvcResult first = mvc.perform(get(OUTLET + "rss")).andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/rss+xml;charset=UTF-8"))
            .andExpect(header().string("Cache-Control", containsString("max-age=60")))
            .andExpect(header().string("Cache-Control", containsString("must-revalidate")))
            .andExpect(header().exists("ETag"))
            .andExpect(header().exists("Last-Modified"))
            .andReturn();
        String etag = first.getResponse().getHeader("ETag");
        assertThat(first.getResponse().getCookie("RSSHUB_SESSION")).isNull();
        assertThat(first.getRequest().getSession(false)).isNull();

        mvc.perform(get(OUTLET + "rss").header("If-None-Match", etag)).andExpect(status().isNotModified());
        // Re-saving the same document must not look like news to a poller: the fingerprint counts change rows.
        long id = db.sql("select post_id from content_change").query(Long.class).single();
        mvc.perform(write(put("/api/posts/" + id), payload("First", "b", null))).andExpect(status().isOk());
        mvc.perform(get(OUTLET + "rss").header("If-None-Match", etag)).andExpect(status().isNotModified());

        create("Second", "b", "published");
        mvc.perform(get(OUTLET + "rss").header("If-None-Match", etag))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", org.hamcrest.Matchers.not(etag)));

        Map<String, String> byFormat = new HashMap<>();
        for (String format : List.of("rss", "atom", "json")) {
            byFormat.put(format, mvc.perform(get(OUTLET + format)).andReturn().getResponse().getHeader("ETag"));
        }
        assertThat(byFormat.values()).as("one document, three serialisations, three validators")
            .doesNotHaveDuplicates();
        // And the changelog never shares a validator with the site feed, even while both carry the same two titles.
        assertThat(mvc.perform(get("/feed.rss")).andReturn().getResponse().getHeader("ETag")).isNotIn(byFormat.values());
        mvc.perform(get(OUTLET + "json").header("If-None-Match", byFormat.get("rss"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("TC-CHG-13: the literal mapping wins over an event stream called changelog")
    void theChangelogShadowIsOnlyTheOnePathWide() throws Exception {
        mvc.perform(write(post("/api/events"),
                Map.of("slug", "changelog", "title", "Release pipeline", "description", "CI runs")))
            .andExpect(status().is2xxSuccessful());
        mvc.perform(write(post("/api/events"),
                Map.of("slug", "builds", "title", "Build pipeline", "description", "CI runs")))
            .andExpect(status().is2xxSuccessful());
        create("A Post", "b", "published");

        assertThat(text(mvc.perform(get(OUTLET + "rss"))))
            .contains("<title>Changelog · Observability Notes</title>")
            .contains("New post: A Post").doesNotContain("Release pipeline");
        // Every other slug under /feed/ still reaches its event stream, so the literal costs this site one name.
        assertThat(text(mvc.perform(get("/feed/builds.rss")))).contains("<title>Build pipeline</title>");
        mvc.perform(get(OUTLET + "opml")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_format"));
    }

    @Test
    @DisplayName("TC-CHG-14: the outlet ships the newest changes and drops the oldest, like the site feed")
    void theItemLimitAppliesHereToo() throws Exception {
        for (int i = 1; i <= 51; i++) {
            create(String.format("Change %02d", i), "b", "published");
        }
        assertThat(text(mvc.perform(get(OUTLET + "rss")))).contains("New post: Change 51")
            .contains("New post: Change 02")
            .doesNotContain("New post: Change 01<");
        JsonNode items = json.readTree(text(mvc.perform(get(OUTLET + "json")))).get("items");
        assertThat(items).hasSize(50);
        List<String> titles = items.valueStream().map(node -> node.get("title").asString()).toList();
        assertThat(titles.get(0)).as("newest first").isEqualTo("New post: Change 51");
        assertThat(titles.get(49)).isEqualTo("New post: Change 02");
    }

    @Test
    @DisplayName("TC-CHG-15/16/17: the editor reads the same history, filtered, and cannot write it")
    void theAdminViewIsReadonlyAndFilterable() throws Exception {
        long kept = create("Kept", "b", "published");
        long dropped = create("Dropped", "b2", "published");
        mvc.perform(write(put("/api/posts/" + kept), payload("Kept", "changed body", null)))
            .andExpect(status().isOk());
        mvc.perform(write(delete("/api/posts/" + dropped))).andExpect(status().isNoContent());

        mvc.perform(get("/api/changelog")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/changelog").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(4))
            // Newest first, the same order the outlet uses: the panel and the feed are one list.
            .andExpect(jsonPath("$[0].action").value("deleted"))
            .andExpect(jsonPath("$[0].slug").value("dropped"))
            .andExpect(jsonPath("$[0].url").value(BASE + "/blog/dropped"))
            .andExpect(jsonPath("$[1].action").value("updated"))
            .andExpect(jsonPath("$[1].postId").value(kept))
            .andExpect(jsonPath("$[2].action").value("created"))
            .andExpect(jsonPath("$[2].fromStatus").doesNotExist())
            .andExpect(jsonPath("$[2].toStatus").value("published"));

        mvc.perform(get("/api/changelog?postId=" + kept).session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].slug").value("kept"));
        mvc.perform(get("/api/changelog?limit=1").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/changelog?limit=0").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/changelog?postId=999999").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(write(post("/api/changelog"), Map.of("action", "created"))).andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("TC-CHG-18: the changelog is advertised where the site feed is")
    void publicPagesLinkToTheChangelog() throws Exception {
        create("Discoverable", "a body", "published");
        String page = text(mvc.perform(get("/blog")));
        for (FeedFormat format : FeedFormat.values()) {
            assertThat(page).contains(BASE + "/feed/changelog." + format.pathToken());
        }
        assertThat(page).contains("title=\"Changelog · Observability Notes (rss)\"");
        assertThat(text(mvc.perform(get("/blog/discoverable")))).contains(BASE + "/feed/changelog.rss");
    }

    // ---------------------------------------------------------------- helpers

    private long create(String title, String bodyMd, String status) throws Exception {
        return json.readTree(text(mvc.perform(write(post("/api/posts"), payload(title, bodyMd, status)))
            .andExpect(status().isCreated()))).get("id").asLong();
    }

    private static Map<String, Object> payload(String title, String bodyMd, String status) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("title", title);
        payload.put("bodyMd", bodyMd);
        payload.put("tags", List.of());
        payload.put("categories", List.of());
        if (status != null) {
            payload.put("status", status);
        }
        return payload;
    }

    private long rowCount() {
        return db.sql("select count(*) from content_change").query(Long.class).single();
    }

    /** The whole table, newest first, as the outlet and the editor panel see it. */
    private List<Map<String, Object>> rows() {
        return db.sql("select id, action, from_status, to_status, post_slug from content_change order by id desc")
            .query((rs, rowNum) -> {
                Map<String, Object> row = new HashMap<>();
                row.put("action", rs.getString("action"));
                row.put("from", rs.getString("from_status"));
                row.put("to", rs.getString("to_status"));
                row.put("slug", rs.getString("post_slug"));
                return row;
            })
            .list();
    }

    private static String between(String text, String open, String close) {
        int start = text.indexOf(open);
        assertThat(start).as("expected %s in the document", open).isGreaterThan(-1);
        return text.substring(start + open.length(), text.indexOf(close, start + open.length()));
    }

    private String text(ResultActions actions) throws Exception {
        MvcResult result = actions.andReturn();
        int status = result.getResponse().getStatus();
        assertThat(status).as("%s %s", result.getRequest().getMethod(), result.getRequest().getRequestURI())
            .isBetween(200, 299);
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) throws Exception {
        return write(request, null);
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, Map<String, Object> body)
        throws Exception {
        Cookie csrf = csrf();
        MockHttpServletRequestBuilder armed = request.session(session)
            .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
        if (body == null) {
            return armed;
        }
        return armed.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }

    private Cookie csrf() throws Exception {
        Cookie cookie = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).as("XSRF-TOKEN must be readable by the browser that echoes it").isNotNull();
        return cookie;
    }
}
