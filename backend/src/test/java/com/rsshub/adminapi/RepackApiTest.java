package com.rsshub.adminapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rsshub.common.JdbcTimes;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * F7 over HTTP: several subscribed feeds published outward as one collection. The two gates this suite exists for
 * are FR-7.1 (every item must trace back to its original — {@code <source>} plus a link that is not ours) and
 * FR-7.2 (summaries ship unless a member explicitly opted into full text, and that opt-in leaves a trail).
 *
 * <p>The outlets are read without a session, like {@code EventsApiTest} does, because an aggregator that had to log
 * in would not be an aggregator.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:repackapi;DB_CLOSE_DELAY=-1",
    "rsshub.operator.username=admin",
    "rsshub.operator.password=a-test-only-password",
    "rsshub.fetch.enabled=false",
    "rsshub.notify.enabled=false",
    "rsshub.publish.base-url=https://repack.example.org",
    "rsshub.publish.site-title=Aggregator"
})
@AutoConfigureMockMvc
class RepackApiTest {

    private static final String PASSWORD = "a-test-only-password";
    private static final String BASE = "https://repack.example.org";
    private static final String SOURCE_A = "https://blog-a.example.org/feed.xml";
    private static final String SOURCE_B = "https://blog-b.example.org/atom.xml";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient db;

    @Autowired
    ObjectMapper json;

    private MockHttpSession session;

    @BeforeEach
    void startWithNothingForwarded() throws Exception {
        db.sql("delete from repack_source_audit").update();
        db.sql("delete from repack_source").update();
        db.sql("delete from repack").update();
        db.sql("delete from entry").update();
        db.sql("delete from scheduled_task").update();
        db.sql("delete from feed").update();

        session = new MockHttpSession();
        mvc.perform(loginInto(session)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("FR-7.1: managing collections is session-only, and publishing one is not")
    void theAdminSurfaceIsGuardedWhileTheOutletIsAnonymous() throws Exception {
        mvc.perform(get("/api/repacks")).andExpect(status().isUnauthorized());
        mvc.perform(csrfProtected(get("/api/repacks").session(session)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        // A collection nobody created has no outlet; an empty URL is a 404, never an empty 200 feed.
        mvc.perform(get("/repack/nope.rss")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_repack"));
        // A logged-in browser still has to echo the CSRF token: the session alone is not write authorisation.
        mvc.perform(post("/api/repacks").session(session).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("slug", "no-csrf", "title", "Anonymous"))))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("FR-7.1: create, list, rename, delete — and the slug is the outlet's identity")
    void collectionCrud() throws Exception {
        mvc.perform(write(post("/api/repacks"),
                Map.of("slug", "Weekly", "title", "Weekly reading", "description", "Best of the week")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.slug").value("weekly"))
            .andExpect(jsonPath("$.members.length()").value(0))
            .andExpect(jsonPath("$.publicUrl").value(BASE + "/repack/weekly.rss"));

        mvc.perform(csrfProtected(get("/api/repacks").session(session)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].memberCount").value(0))
            .andExpect(jsonPath("$[0].slug").value("weekly"));

        mvc.perform(write(post("/api/repacks"), Map.of("slug", "weekly", "title", "Duplicate")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("urn:rsshub:repack_exists"));

        mvc.perform(write(post("/api/repacks"), Map.of("slug", "not a slug!", "title", "Bad")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:invalid_slug"));

        mvc.perform(write(patch("/api/repacks/weekly"), Map.of("title", "Weekly picks")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Weekly picks"))
            .andExpect(jsonPath("$.description").value("Best of the week"));

        mvc.perform(csrfProtected(delete("/api/repacks/weekly").session(session))).andExpect(status().isNoContent());
        mvc.perform(csrfProtected(get("/api/repacks/weekly").session(session)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_repack"));
    }

    @Test
    @DisplayName("FR-7.1: every item names the feed it came from and links to the original, in all three formats")
    void membersShipTheirSourceAndOriginalLink() throws Exception {
        long a = feed(SOURCE_A, "Blog A");
        long b = feed(SOURCE_B, "Blog B");
        createRepack("weekly", "Weekly reading");
        addSource("weekly", a);
        addSource("weekly", b);
        entry(a, "a1", "https://blog-a.example.org/posts/one", "A one",
            "A summary", "A body with markup", Instant.now().minusSeconds(300));
        entry(b, "b1", "https://blog-b.example.org/posts/two", "B two",
            "B summary", "B body", Instant.now().minusSeconds(100));

        String rss = outlet("/repack/weekly.rss");
        assertThat(rss).contains("<title>Weekly reading</title>")
            .contains("<atom:link href=\"" + BASE + "/repack/weekly.rss\" rel=\"self\"")
            // Two members, two different origins, each item naming its own.
            .contains("<source url=\"" + SOURCE_A + "\">Blog A</source>")
            .contains("<source url=\"" + SOURCE_B + "\">Blog B</source>")
            // The link is the original's page, not a page on this host (FR-7.1's traceability rule).
            .contains("<link>https://blog-a.example.org/posts/one</link>")
            .contains("<guid isPermaLink=\"true\">https://blog-a.example.org/posts/one</guid>")
            // The source's own categories travel with the item.
            .contains("<category>obs</category>");

        JsonNode feed = json.readTree(outlet("/repack/weekly.json"));
        assertThat(feed.get("title").asString()).isEqualTo("Weekly reading");
        JsonNode newest = feed.get("items").get(0);
        assertThat(newest.get("url").asString()).isEqualTo("https://blog-b.example.org/posts/two");
        assertThat(newest.get("external_url").asString()).isEqualTo(SOURCE_B);
        assertThat(newest.get("rss_hub_source").get("title").asString()).isEqualTo("Blog B");
        // Newest first across members, not grouped by feed: a collection reads as one feed.
        assertThat(feed.get("items").get(1).get("external_url").asString()).isEqualTo(SOURCE_A);

        String atom = outlet("/repack/weekly.atom");
        assertThat(atom).contains("<source>")
            .contains("<id>" + SOURCE_B + "</id>")
            .contains("<title>Blog B</title>");

        // The member view is where the operator sees what is actually going out.
        mvc.perform(csrfProtected(get("/api/repacks/weekly").session(session)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.members.length()").value(2))
            .andExpect(jsonPath("$.members[0].canonicalUrl").value(SOURCE_A))
            .andExpect(jsonPath("$.members[0].entryCount").value(1))
            .andExpect(jsonPath("$.members[0].fullContent").value(false));
    }

    @Test
    @DisplayName("FR-7.2: summaries by default, full text only after a per-site opt-in — and the opt-in is logged")
    void fullContentIsPerMemberAndAudited() throws Exception {
        long a = feed(SOURCE_A, "Blog A");
        long b = feed(SOURCE_B, "Blog B");
        createRepack("weekly", "Weekly reading");
        addSource("weekly", a);
        addSource("weekly", b);
        entry(a, "a1", "https://blog-a.example.org/posts/one", "A one",
            "A summary text", "A-private-full-body-marker", Instant.now().minusSeconds(300));
        entry(b, "b1", "https://blog-b.example.org/posts/two", "B two",
            "B summary text", "B-private-full-body-marker", Instant.now().minusSeconds(100));

        String summaryOnly = outlet("/repack/weekly.rss");
        assertThat(summaryOnly).contains("<description>A summary text</description>")
            .doesNotContain("A-private-full-body-marker")
            .doesNotContain("B-private-full-body-marker");

        mvc.perform(write(patch("/api/repacks/weekly/feeds/" + a), Map.of("fullContent", true)))
            .andExpect(status().isOk());
        assertThat(fullContentFlags()).containsEntry("Blog A", true).containsEntry("Blog B", false);

        String withBody = outlet("/repack/weekly.rss");
        // The flag is per member: turning one site loose does not republish the other's bodies.
        assertThat(withBody).contains("A-private-full-body-marker").doesNotContain("B-private-full-body-marker");
        assertThat(json.readTree(outlet("/repack/weekly.json")).get("items").get(1).get("content_html").asString())
            .isEqualTo("A-private-full-body-marker");

        // Idempotent writes stay out of the log: a trail of every click would bury the two acts that mattered.
        mvc.perform(write(patch("/api/repacks/weekly/feeds/" + a), Map.of("fullContent", true)))
            .andExpect(status().isOk());

        List<String> trail = auditActions();
        assertThat(trail).containsExactly("full_content_on", "add", "add");
        assertThat(auditRows()).isEqualTo(3L);
        JsonNode first = json.readTree(text(mvc.perform(csrfProtected(get("/api/repacks/weekly/audit")
            .session(session)))));
        assertThat(first.get(0).get("action").asString()).isEqualTo("full_content_on");
        assertThat(first.get(0).get("feedId").asLong()).isEqualTo(a);
        assertThat(first.get(0).get("fullContent").asBoolean()).isTrue();

        mvc.perform(write(patch("/api/repacks/weekly/feeds/" + a), Map.of("fullContent", false)))
            .andExpect(status().isOk());
        assertThat(outlet("/repack/weekly.rss")).doesNotContain("A-private-full-body-marker");
        assertThat(auditActions()).containsExactly("full_content_off", "full_content_on", "add", "add");
    }

    @Test
    @DisplayName("FR-7.1: what the operator dismissed stays out of the collection")
    void dismissedEntriesAreNeverForwarded() throws Exception {
        long a = feed(SOURCE_A, "Blog A");
        createRepack("weekly", "Weekly reading");
        addSource("weekly", a);
        entry(a, "kept", "https://blog-a.example.org/kept", "Kept item", "summary", "body",
            Instant.now().minusSeconds(300));
        db.sql("insert into entry (feed_id, guid, url, title, summary, published_at, read_state) "
                + "values (?, 'gone', 'https://blog-a.example.org/gone', 'Dismissed item', 'summary', ?, 'dismissed')")
            .params(a, JdbcTimes.toSql(Instant.now().minusSeconds(100)))
            .update();

        String rss = outlet("/repack/weekly.rss");
        assertThat(rss).contains("Kept item").doesNotContain("Dismissed item");
        mvc.perform(csrfProtected(get("/api/repacks/weekly").session(session)))
            .andExpect(jsonPath("$.members[0].entryCount").value(1));
    }

    @Test
    @DisplayName("FR-7.1: an item keeps the id the original gave it; a non-URL id degrades to the URL, then to a URN")
    void idsStayStableAcrossTheRepost() throws Exception {
        long a = feed(SOURCE_A, "Blog A");
        createRepack("weekly", "Weekly reading");
        addSource("weekly", a);
        // A source that publishes permalinks as guids: the repack must not mint a second identity for the same post.
        db.sql("insert into entry (feed_id, guid, url, title, summary, published_at) values (?, ?, ?, ?, ?, ?)")
            .params(a, "https://blog-a.example.org/posts/exact", "https://blog-a.example.org/posts/exact",
                "Permalink guid", "s", JdbcTimes.toSql(Instant.now().minusSeconds(400)))
            .update();
        entry(a, "tag:blog-a.example.org,2026:op-7", "https://blog-a.example.org/posts/opaque", "Opaque guid",
            "s", "b", Instant.now().minusSeconds(300));
        entry(a, "12345", null, "No link anywhere", "s", "b", Instant.now().minusSeconds(200));
        UUID publicId = db.sql("select public_id from entry where guid = ?").param("12345")
            .query(UUID.class).single();

        String rss = outlet("/repack/weekly.rss");
        assertThat(rss).contains("<guid isPermaLink=\"true\">https://blog-a.example.org/posts/exact</guid>")
            // A guid that is not an absolute URL cannot claim isPermaLink, so the item's own page becomes the id.
            .contains("<guid isPermaLink=\"true\">https://blog-a.example.org/posts/opaque</guid>")
            .contains("<guid isPermaLink=\"false\">urn:rsshub:entry:" + publicId + "</guid>");
        // Atom's <id> has to be an IRI, so the URN form is what a linkless entry publishes as.
        assertThat(outlet("/repack/weekly.atom"))
            .contains("<id>urn:rsshub:entry:" + publicId + "</id>");
    }

    @Test
    @DisplayName("FR-7.1: the outlet is a pollable document — validators, no session, and a 404 for a bad suffix")
    void theOutletBehavesLikeAPollTarget() throws Exception {
        long a = feed(SOURCE_A, "Blog A");
        createRepack("weekly", "Weekly reading");
        addSource("weekly", a);
        entry(a, "a1", "https://blog-a.example.org/posts/one", "A one", "A's summary", "A's body",
            Instant.now().minusSeconds(300));

        MvcResult first = mvc.perform(get("/repack/weekly.rss"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/rss+xml;charset=UTF-8"))
            .andExpect(header().string("Cache-Control", containsString("must-revalidate")))
            .andExpect(header().exists("ETag"))
            .andReturn();
        String etag = first.getResponse().getHeader("ETag");
        mvc.perform(get("/repack/weekly.rss").header("If-None-Match", etag)).andExpect(status().isNotModified());
        mvc.perform(get("/repack/weekly.atom").header("If-None-Match", etag)).andExpect(status().isOk());
        assertThat(first.getRequest().getSession(false)).as("a poller must not be remembered").isNull();

        // A retraction changes the id set, so the validator has to move with it (03 §6.4).
        db.sql("delete from entry where guid = ?").param("a1").update();
        mvc.perform(get("/repack/weekly.rss").header("If-None-Match", etag))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", not(etag)));

        mvc.perform(get("/repack/weekly.opml")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_format"));
    }

    @Test
    @DisplayName("FR-7.1: a collection with no members is a legal empty outlet, not a failure")
    void anEmptyCollectionStillShips() throws Exception {
        createRepack("weekly", "Weekly reading");
        assertThat(outlet("/repack/weekly.rss")).contains("<title>Weekly reading</title>")
            .doesNotContain("<item>");
        assertThat(json.readTree(outlet("/repack/weekly.json")).get("items").isArray()).isTrue();
    }

    @Test
    @DisplayName("FR-7.1: membership refuses a stranger, a duplicate and a removal that already happened")
    void membershipErrors() throws Exception {
        long a = feed(SOURCE_A, "Blog A");
        createRepack("weekly", "Weekly reading");

        mvc.perform(write(post("/api/repacks/weekly/feeds"), Map.of("feedId", 9999)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:feed_not_found"));
        mvc.perform(write(post("/api/repacks/nope/feeds"), Map.of("feedId", a)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_repack"));
        addSource("weekly", a);
        mvc.perform(write(post("/api/repacks/weekly/feeds"), Map.of("feedId", a)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("urn:rsshub:already_repacked"));

        mvc.perform(write(patch("/api/repacks/weekly/feeds/9999"), Map.of("fullContent", true)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:not_a_member"));

        mvc.perform(csrfProtected(delete("/api/repacks/weekly/feeds/" + a).session(session)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.members.length()").value(0));
        mvc.perform(csrfProtected(delete("/api/repacks/weekly/feeds/" + a).session(session)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:not_a_member"));
    }

    @Test
    @DisplayName("FR-7.2: unsubscribing or deleting a collection removes the outlet but never the audit trail")
    void theTrailOutlivesTheRowsItDescribes() throws Exception {
        long a = feed(SOURCE_A, "Blog A");
        createRepack("weekly", "Weekly reading");
        addSource("weekly", a);
        mvc.perform(write(patch("/api/repacks/weekly/feeds/" + a), Map.of("fullContent", true)))
            .andExpect(status().isOk());
        assertThat(auditRows()).isEqualTo(2L);

        // A subscription is deleted the way the fetcher gives up on it: the cascade takes the membership…
        db.sql("delete from feed where id = ?").param(a).update();
        mvc.perform(csrfProtected(get("/api/repacks/weekly").session(session)))
            .andExpect(jsonPath("$.members.length()").value(0));
        // …and even a whole collection going away leaves the two decisions behind.
        mvc.perform(csrfProtected(delete("/api/repacks/weekly").session(session))).andExpect(status().isNoContent());
        assertThat(auditRows()).isEqualTo(2L);
        assertThat(db.sql("select count(*) from repack_source").query(Long.class).single()).isZero();
    }

    private long feed(String canonicalUrl, String title) {
        db.sql("insert into feed (canonical_url, requested_url, title, state) values (?, ?, ?, 'active')")
            .params(canonicalUrl, canonicalUrl, title)
            .update();
        return db.sql("select id from feed where canonical_url = ?").param(canonicalUrl).query(Long.class).single();
    }

    private long entry(long feedId, String guid, String url, String title, String summary, String contentHtml,
        Instant publishedAt) {
        db.sql("insert into entry (feed_id, guid, url, title, summary, content_html, published_at, categories) "
                + "values (?, ?, ?, ?, ?, ?, ?, '[\"obs\"]')")
            .params(feedId, guid, url, title, summary, contentHtml, JdbcTimes.toSql(publishedAt))
            .update();
        return db.sql("select id from entry where feed_id = ? and guid = ?").params(feedId, guid)
            .query(Long.class).single();
    }

    private void createRepack(String slug, String title) throws Exception {
        mvc.perform(write(post("/api/repacks"), Map.of("slug", slug, "title", title)))
            .andExpect(status().isCreated());
    }

    private void addSource(String slug, long feedId) throws Exception {
        mvc.perform(write(post("/api/repacks/" + slug + "/feeds"), Map.of("feedId", feedId)))
            .andExpect(status().isOk());
    }

    /** Each member's flag, keyed by the feed's title, read back through the admin API the screen uses. */
    private Map<String, Boolean> fullContentFlags() throws Exception {
        JsonNode detail = json.readTree(text(mvc.perform(csrfProtected(get("/api/repacks/weekly").session(session)))));
        Map<String, Boolean> flags = new java.util.LinkedHashMap<>();
        detail.get("members").valueStream().forEach(member ->
            flags.put(member.get("title").asString(), member.get("fullContent").asBoolean()));
        return flags;
    }

    private List<String> auditActions() throws Exception {
        return json.readTree(text(mvc.perform(csrfProtected(get("/api/repacks/weekly/audit").session(session)))))
            .valueStream().map(node -> node.get("action").asString()).toList();
    }

    private long auditRows() {
        return db.sql("select count(*) from repack_source_audit").query(Long.class).single();
    }

    /** Reads an anonymous outlet as a subscriber would: no session, no CSRF, UTF-8 body. */
    private String outlet(String path) throws Exception {
        return text(mvc.perform(get(path)).andExpect(status().isOk()).andReturn());
    }

    private String text(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String text(ResultActions actions) throws Exception {
        return text(actions.andReturn());
    }

    private MockHttpServletRequestBuilder loginInto(MockHttpSession target) throws Exception {
        Cookie csrf = csrf();
        return post("/api/auth/login").session(target)
            .param("username", "admin").param("password", PASSWORD)
            .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return jsonRequest(csrfProtected(request.session(session)), body);
    }

    private MockHttpServletRequestBuilder jsonRequest(MockHttpServletRequestBuilder request, Object body)
        throws Exception {
        return request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }

    private MockHttpServletRequestBuilder csrfProtected(MockHttpServletRequestBuilder request) throws Exception {
        Cookie csrf = csrf();
        return request.cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
    }

    /**
     * One cookie value per request: the token is minted per session that has none, so reading it twice would echo
     * the second value against the first one.
     */
    private Cookie csrf() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/me")).andReturn();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).as("XSRF-TOKEN must be readable by the browser that echoes it").isNotNull();
        return cookie;
    }
}
