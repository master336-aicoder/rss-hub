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

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
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
 * M3's machine side (FR-3.1 to FR-3.4): a scoped token writes events, those events come back out of the three
 * public outlets, and turning a stream into a virtual feed puts its history in the inbox without any fetcher
 * touching it.
 *
 * <p>The outlets and the permalink page are read without a session, exactly like {@code PublishApiTest} does, and
 * every bearer call is sent without one too: attaching the operator's session would let the request pass on the
 * cookie and the scope under test would never be exercised.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:eventsapi;DB_CLOSE_DELAY=-1",
    "rsshub.operator.username=admin",
    "rsshub.operator.password=a-test-only-password",
    "rsshub.fetch.enabled=false",
    "rsshub.publish.base-url=https://events.example.org",
    "rsshub.publish.site-title=Signals"
})
@AutoConfigureMockMvc
class EventsApiTest {

    private static final String PASSWORD = "a-test-only-password";
    private static final String BASE = "https://events.example.org";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient db;

    @Autowired
    ObjectMapper json;

    private MockHttpSession session;

    @BeforeEach
    void startFromAnEmptyMachineSide() throws Exception {
        db.sql("delete from entry").update();
        db.sql("delete from scheduled_task").update();
        db.sql("delete from feed").update();
        db.sql("delete from event_item").update();
        db.sql("delete from event_stream").update();
        db.sql("delete from api_token").update();

        session = new MockHttpSession();
        mvc.perform(loginInto(session)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("FR-3.1: the secret is shown once, and the list can never reveal it again")
    void aTokenIsReturnedOnceAndStoredAsAHash() throws Exception {
        MvcResult created = mvc.perform(write(post("/api/tokens"), Map.of("name", "nightly-ci", "scope", "ingest")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("nightly-ci"))
            .andExpect(jsonPath("$.scope").value("ingest"))
            .andReturn();
        JsonNode body = json.readTree(text(created));
        String secret = body.get("secret").asString();
        long id = body.get("id").asLong();
        // 32 random bytes, base64url without padding.
        assertThat(secret).hasSize(43);
        // Only the digest is stored, so a database dump cannot be turned into a working credential.
        assertThat(db.sql("select token_hash from api_token").query(String.class).single()).isNotEqualTo(secret);

        assertThat(text(mvc.perform(csrfProtected(get("/api/tokens")).session(session))))
            .contains("\"name\":\"nightly-ci\"")
            .doesNotContain("secret");

        mvc.perform(csrfProtected(delete("/api/tokens/" + id).session(session))).andExpect(status().isNoContent());
        mvc.perform(csrfProtected(delete("/api/tokens/" + id).session(session)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_token"));
        // Revocation is a timestamp, not a delete: "when did CI lose its key" has to stay answerable.
        assertThat(db.sql("select count(*) from api_token").query(Long.class).single()).isEqualTo(1L);
        ingest(secret, Map.of("title", "after revocation")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("FR-3.1: a scope is a capability, not a label")
    void eachScopeReachesExactlyTheEndpointsItNames() throws Exception {
        String ingestSecret = token("ci-ingest", "ingest");
        String readSecret = token("reader", "read");
        String adminSecret = token("automation", "admin");

        ingest(ingestSecret, Map.of("title", "one")).andExpect(status().isOk());
        ingest(readSecret, Map.of("title", "two")).andExpect(status().isForbidden());
        // Admin is the top of the ladder, so it reaches the ingest route too; read never writes anywhere.
        ingest(adminSecret, Map.of("title", "three")).andExpect(status().isOk());

        // An ingest token that could list the subscriptions would be a far bigger credential than a CI job needs.
        bearer(readSecret, get("/api/feeds")).andExpect(status().isOk());
        bearer(ingestSecret, get("/api/feeds")).andExpect(status().isForbidden());
        bearer(adminSecret, get("/api/feeds")).andExpect(status().isOk());

        // The admin scope is the one that owns the ordinary admin surface, and it owns it fully.
        mvc.perform(post("/api/events").header("Authorization", "Bearer " + adminSecret)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("slug", "admin-made", "title", "Made by a token"))))
            .andExpect(status().isOk());
        bearer(adminSecret, get("/api/events/admin-made")).andExpect(status().isOk());

        // Nothing about a token is optional: an unauthenticated POST never reaches the ingest service. Which 4xx
        // comes back depends on whether CSRF or the entry point fires first, and both are refusals.
        mvc.perform(post("/api/ingest/events").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("title", "anonymous"))))
            .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("FR-3.2: the same key updates in place instead of inserting a second copy")
    void ingestIsIdempotentPerDedupeKey() throws Exception {
        String secret = token("ci", "ingest");

        JsonNode first = json.readTree(text(ingest(secret, Map.of("title", "node-3 disk full",
            "summary", "92% used", "dedupeKey", "disk/node-3"))));
        String id = first.get("id").asString();
        assertThat(first.get("created").asBoolean()).isTrue();

        JsonNode replay = json.readTree(text(ingest(secret, Map.of("title", "node-3 disk full",
            "summary", "92% used", "dedupeKey", "disk/node-3"))));
        assertThat(replay.get("created").asBoolean()).isFalse();
        assertThat(replay.get("id").asString()).isEqualTo(id);
        assertThat(eventRowCount()).isEqualTo(1L);

        // Same key, changed content: one row, its body replaced, its identifier kept — so a subscriber sees the
        // alert escalating rather than a second incident to triage.
        JsonNode changed = json.readTree(text(ingest(secret, Map.of("title", "node-3 disk full",
            "summary", "98% used", "dedupeKey", "disk/node-3"))));
        assertThat(changed.get("id").asString()).isEqualTo(id);
        assertThat(changed.get("created").asBoolean()).isFalse();
        assertThat(eventRowCount()).isEqualTo(1L);
        assertThat(outlet("/feed/events.rss")).contains("98% used").doesNotContain("92% used");
        assertThat(json.readTree(outlet("/feed/events.json")).get("items").get(0).get("id").asString())
            .isEqualTo(BASE + "/event/events/" + id);
    }

    @Test
    @DisplayName("FR-3.1/3.2: no key means the content hash is the key, and the default stream mints itself")
    void keylessIngestDedupesOnContentAndTheDefaultStreamExistsOnFirstUse() throws Exception {
        assertThat(db.sql("select count(*) from event_stream").query(Long.class).single()).isZero();
        String secret = token("ci", "ingest");

        JsonNode first = json.readTree(text(ingest(secret, Map.of("title", "backup skipped",
            "summary", "no changes"))));
        assertThat(first.get("stream").asString()).isEqualTo("events");
        assertThat(first.get("created").asBoolean()).isTrue();

        // The retry of a POST that timed out carries the same body, so it is the same event.
        assertThat(json.readTree(text(ingest(secret, Map.of("title", "backup skipped",
            "summary", "no changes")))).get("created").asBoolean()).isFalse();
        assertThat(json.readTree(text(ingest(secret, Map.of("title", "backup skipped",
            "summary", "lock held")))).get("created").asBoolean()).isTrue();
        assertThat(eventRowCount()).isEqualTo(2L);

        // Whitespace is not a new incident: the hash is over the collapsed text, as feedstore does for real entries.
        assertThat(json.readTree(text(ingest(secret, Map.of("title", "  backup   skipped ",
            "summary", "no\nchanges")))).get("created").asBoolean()).isFalse();

        // A slug named in the path has to exist: a typo there must fail loudly, not create a stream nobody reads.
        ingest(secret, "eventz", Map.of("title", "typo"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_event_stream"));
    }

    @Test
    @DisplayName("FR-3.3: the stream is managed through the session and PATCH only changes what it sends")
    void streamCrudKeepsTheSlugStableAndTheUnsentFieldsAlone() throws Exception {
        mvc.perform(write(post("/api/events"),
                Map.of("slug", "builds", "title", "Build results", "description", "CI outcomes")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.slug").value("builds"))
            .andExpect(jsonPath("$.visibility").value("internal"))
            .andExpect(jsonPath("$.feedUrlBase").value(BASE));

        mvc.perform(write(post("/api/events"), Map.of("slug", "builds", "title", "Duplicate")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("urn:rsshub:stream_exists"));

        mvc.perform(write(post("/api/events"), Map.of("slug", "Build Results", "title", "Spaces")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:invalid_slug"));

        mvc.perform(write(patch("/api/events/builds"), Map.of("title", "Build outcomes")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Build outcomes"))
            .andExpect(jsonPath("$.description").value("CI outcomes"));

        ingest(token("ci", "ingest"), "builds", Map.of("title", "job 41 failed"))
            .andExpect(status().isOk());
        JsonNode listed = json.readTree(text(mvc.perform(csrfProtected(get("/api/events").session(session)))));
        assertThat(listed.get(0).get("itemCount").asLong()).isEqualTo(1L);

        mvc.perform(csrfProtected(delete("/api/events/builds").session(session))).andExpect(status().isNoContent());
        mvc.perform(csrfProtected(get("/api/events/builds").session(session)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_event_stream"));
    }

    @Test
    @DisplayName("FR-3.4: one stream, three outlets, and the validators a poller expects")
    void theStreamPublishesOutwardWithoutASession() throws Exception {
        createStream("deploys", "Deploys");
        String secret = token("ci", "ingest");
        String id = json.readTree(text(ingest(secret, "deploys", Map.of("title", "web 1.4.2 shipped",
            "summary", "rolled out over 90s", "tags", List.of("web", "release"),
            "url", "https://ci.example.org/builds/142")))).get("id").asString();
        String permalink = BASE + "/event/deploys/" + id;

        String rss = outlet("/feed/deploys.rss");
        String atom = outlet("/feed/deploys.atom");
        JsonNode feed = json.readTree(outlet("/feed/deploys.json"));

        assertThat(rss).contains("<title>Deploys</title>")
            .contains("<atom:link href=\"" + BASE + "/feed/deploys.rss\" rel=\"self\"")
            .contains("<category>web</category>")
            .contains("<guid isPermaLink=\"true\">" + permalink + "</guid>");
        assertThat(atom).contains("<id>" + permalink + "</id>")
            .contains("<link rel=\"self\" type=\"application/atom+xml\" href=\"" + BASE + "/feed/deploys.atom\"/>");
        assertThat(feed.get("title").asString()).isEqualTo("Deploys");
        assertThat(feed.get("home_page_url").asString()).isEqualTo(BASE + "/");
        assertThat(feed.get("items").get(0).get("tags").valueStream().map(JsonNode::asString).toList())
            .containsExactly("web", "release");
        // An event with its own URL (a build page, a dashboard) publishes that; the permalink is the fallback.
        assertThat(feed.get("items").get(0).get("url").asString()).isEqualTo("https://ci.example.org/builds/142");

        MvcResult first = mvc.perform(get("/feed/deploys.atom"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/atom+xml;charset=UTF-8"))
            .andExpect(header().string("Cache-Control", containsString("must-revalidate")))
            .andExpect(header().exists("ETag"))
            .andReturn();
        String etag = first.getResponse().getHeader("ETag");
        mvc.perform(get("/feed/deploys.atom").header("If-None-Match", etag)).andExpect(status().isNotModified());
        // Another format of the same slice is another document, so it must not inherit this one's validator.
        mvc.perform(get("/feed/deploys.json").header("If-None-Match", etag)).andExpect(status().isOk());
        assertThat(first.getRequest().getSession(false)).as("a poller must not be remembered").isNull();

        ingest(secret, "deploys", Map.of("title", "web 1.4.3 shipped", "summary", "again"))
            .andExpect(status().isOk());
        mvc.perform(get("/feed/deploys.atom").header("If-None-Match", etag))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", not(etag)));

        // A suffix this site does not publish is a 404 about the URL, and a stream nobody created is too.
        mvc.perform(get("/feed/deploys.opml")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_format"));
        mvc.perform(get("/feed/nope.rss")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_event_stream"));
    }

    @Test
    @DisplayName("FR-3.3: virtual_feed bridges the history into the inbox, internal takes it back out")
    void bridgingAStreamIntoTheInboxOwnsASyntheticFeedThatIsNeverFetched() throws Exception {
        createStream("alerts", "Alerts");
        String secret = token("ci", "ingest");
        ingest(secret, "alerts", Map.of("title", "queue depth 1200", "summary", "consumer lag"));
        ingest(secret, "alerts", Map.of("title", "queue depth 40", "summary", "recovering"));

        mvc.perform(write(patch("/api/events/alerts/visibility"), Map.of("visibility", "virtual_feed")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.visibility").value("virtual_feed"));

        // Both events written before the switch are readable now, which is the reason the switch exists.
        long feedId = db.sql("select id from feed where requested_url = 'event:alerts'")
            .query(Long.class).optional().orElseThrow();
        assertThat(entriesFor(feedId)).isEqualTo(2L);
        // The marker feed is parked: the fetcher only selects active/error/muted rows, so a virtual source cannot
        // be polled, cannot fail, and cannot be asked for bytes at a URL that does not exist.
        assertThat(db.sql("select state from feed where id = ?").param(feedId).query(String.class).single())
            .isEqualTo("paused");
        assertThat(db.sql("select count(*) from scheduled_task where kind = 'fetch' and ref_id = ?")
            .param(feedId).query(Long.class).single()).isZero();

        ingest(secret, "alerts", Map.of("title", "queue depth 7", "summary", "healthy"));
        assertThat(entriesFor(feedId)).isEqualTo(3L);
        // The inbox row's guid is the event's permalink, so the same event cannot be stored twice by the bridge.
        assertThat(db.sql("select count(distinct guid) from entry where feed_id = ?")
            .param(feedId).query(Long.class).single()).isEqualTo(3L);

        mvc.perform(write(patch("/api/events/alerts/visibility"), Map.of("visibility", "internal")))
            .andExpect(status().isOk());
        assertThat(db.sql("select count(*) from feed where requested_url = 'event:alerts'")
            .query(Long.class).single()).isZero();
        assertThat(db.sql("select count(*) from entry").query(Long.class).single()).isZero();
        // Switching views is not deleting what the machine posted: the events themselves are untouched.
        assertThat(eventRowCount()).isEqualTo(3L);

        ingest(secret, "alerts", Map.of("title", "queue depth 3", "summary", "fine")).andExpect(status().isOk());
        assertThat(entriesFor(feedId)).as("an internal stream no longer writes to the inbox").isZero();
    }

    @Test
    @DisplayName("FR-1.3: the guid an event publishes is a page a human can open")
    void thePermalinkPageRendersAndAdvertisesTheStreamsOwnOutlets() throws Exception {
        createStream("ci", "CI");
        String id = json.readTree(text(ingest(token("ci", "ingest"), "ci",
            Map.of("title", "lint failed on main", "summary", "eslint exited 1", "tags", List.of("lint")))))
            .get("id").asString();

        MvcResult page = mvc.perform(get("/event/ci/" + id))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", containsString("text/html")))
            .andReturn();
        String html = text(page);
        // The discovery block is the point: href and type both, in whatever order the layout template emits them.
        assertThat(html).contains("lint failed on main").contains("eslint exited 1")
            .contains("href=\"" + BASE + "/feed/ci.atom\" type=\"application/atom+xml;charset=UTF-8\"")
            .contains("href=\"" + BASE + "/feed/ci.rss\" type=\"application/rss+xml;charset=UTF-8\"")
            .contains("rel=\"alternate\"");
        assertThat(page.getRequest().getSession(false)).as("a crawler runs no JavaScript and needs no session").isNull();

        mvc.perform(get("/event/ci/00000000-0000-0000-0000-000000000000"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_event"));
        mvc.perform(get("/event/nosuch/" + id)).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_event_stream"));
    }

    private long eventRowCount() {
        return db.sql("select count(*) from event_item").query(Long.class).single();
    }

    private long entriesFor(long feedId) {
        return db.sql("select count(*) from entry where feed_id = ?").param(feedId).query(Long.class).single();
    }

    /** Reads an anonymous outlet as a subscriber would: no session, no CSRF, UTF-8 body. */
    private String outlet(String path) throws Exception {
        return text(mvc.perform(get(path)).andExpect(status().isOk()).andReturn());
    }

    private String token(String name, String scope) throws Exception {
        return json.readTree(text(mvc.perform(write(post("/api/tokens"),
            Map.of("name", name, "scope", scope))))).get("secret").asString();
    }

    private void createStream(String slug, String title) throws Exception {
        mvc.perform(write(post("/api/events"), Map.of("slug", slug, "title", title))).andExpect(status().isOk());
    }

    /** The body-named route: no slug in the path, so the default stream is minted on first use. */
    private ResultActions ingest(String secret, Map<String, Object> body) throws Exception {
        return bearer(secret, jsonRequest(post("/api/ingest/events"), body));
    }

    /** The path-named route a pipeline with several streams uses, and the one that refuses an unknown slug. */
    private ResultActions ingest(String secret, String slug, Map<String, Object> body) throws Exception {
        return bearer(secret, jsonRequest(post("/api/ingest/events/" + slug), body));
    }

    private ResultActions bearer(String secret, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + secret));
    }

    private MockHttpServletRequestBuilder jsonRequest(MockHttpServletRequestBuilder request, Object body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
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
