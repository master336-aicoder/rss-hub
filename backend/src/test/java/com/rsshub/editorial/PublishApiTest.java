package com.rsshub.editorial;

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

import com.rsshub.feedfetch.LocalFeedServer;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * M2's acceptance gate (03 §12): a post written through the editor is the same document in three syndication formats
 * and on one server-rendered page, and every one of those answers with real HTTP validators to an anonymous reader.
 *
 * <p>Everything below reads the outlets without a session, because that is the only way a feed reader will ever reach
 * them. If any of these calls created a session, a thousand polling subscribers would fill the operator's heap.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:publishapi;DB_CLOSE_DELAY=-1",
    "rsshub.operator.username=admin",
    "rsshub.operator.password=a-test-only-password",
    "rsshub.fetch.enabled=false",
    "rsshub.publish.base-url=https://notes.example.org",
    "rsshub.publish.site-title=Observability Notes",
    "rsshub.publish.description=Notes about kernels, tracing and time series"
})
@AutoConfigureMockMvc
class PublishApiTest {

    private static final String PASSWORD = "a-test-only-password";
    private static final String BASE = "https://notes.example.org";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient db;

    @Autowired
    ObjectMapper json;

    private MockHttpSession session;

    @BeforeEach
    void startFromAnEmptySite() throws Exception {
        // Children before parent: the FKs allow the cascade but the test is the one deleting, so the order is ours.
        db.sql("delete from post_tag").update();
        db.sql("delete from post_category").update();
        db.sql("delete from tag").update();
        db.sql("delete from category").update();
        db.sql("delete from post").update();

        session = new MockHttpSession();
        Cookie csrf = csrf();
        mvc.perform(post("/api/auth/login").session(session)
                .param("username", "admin").param("password", PASSWORD)
                .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("FR-1.2: one published post, three outlets, one server-rendered page")
    void aPublishedPostIsInTheSameDocumentInThreeSerialisations() throws Exception {
        long id = create("Using eBPF Without a Kernel Module", """
            A short note about **verifier** limits.

            The interesting part is the map.
            """, "published", List.of("ebpf", "kernel"), List.of("Notes"));

        String rss = body(mvc.perform(get("/feed.rss")));
        String atom = body(mvc.perform(get("/feed.atom")));
        String jsonFeed = body(mvc.perform(get("/feed.json")));

        String permalink = BASE + "/blog/using-ebpf-without-a-kernel-module";
        assertThat(rss).contains(permalink).contains("<title>Using eBPF Without a Kernel Module</title>")
            .contains("<category>ebpf</category>").contains("<category>kernel</category>")
            .contains("<atom:link href=\"" + BASE + "/feed.rss\" rel=\"self\"")
            .doesNotContain("rel=\"hub\"");
        assertThat(atom).contains("<id>" + permalink + "</id>")
            .contains("<link rel=\"alternate\" type=\"text/html\" href=\"" + permalink + "\"/>")
            .contains("<link rel=\"self\" type=\"application/atom+xml\" href=\"" + BASE + "/feed.atom\"/>");
        JsonNode document = json.readTree(jsonFeed);
        assertThat(document.get("version").asString()).isEqualTo("https://jsonfeed.org/version/1.1");
        assertThat(document.get("feed_url").asString()).isEqualTo(BASE + "/feed.json");
        assertThat(document.get("items").get(0).get("id").asString()).isEqualTo(permalink);
        assertThat(document.get("items").get(0).get("tags").valueStream().map(JsonNode::asString)
            .toList()).containsExactly("ebpf", "kernel");
        // The same post through the three writers must agree on what it is called and where it lives.
        assertThat(document.get("title").asString()).isEqualTo("Observability Notes");
        assertThat(rss).contains("<title>Observability Notes</title>");
        assertThat(atom).contains("<title>Observability Notes</title>");
        assertThat(id).isPositive();
    }

    @Test
    @DisplayName("FR-1.4: the validators an aggregator polls on, answered without a session")
    void outletsCarryValidatorsAndAnswerThirtyFour() throws Exception {
        create("First", "the body", "published", List.of("a"), List.of());

        MvcResult first = mvc.perform(get("/feed.rss")).andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/rss+xml;charset=UTF-8"))
            // 03 §6.4: short, private, and always revalidated — a shared cache must never serve the operator's inbox.
            .andExpect(header().string("Cache-Control", containsString("must-revalidate")))
            .andExpect(header().string("Cache-Control", containsString("private")))
            .andExpect(header().string("Cache-Control", containsString("max-age=60")))
            .andExpect(header().exists("ETag"))
            .andExpect(header().exists("Last-Modified"))
            .andReturn();
        String etag = first.getResponse().getHeader("ETag");
        assertThat(etag).as("weak: the same content may serialise differently on another dialect").startsWith("W/\"");
        assertThat(first.getResponse().getCookie("RSSHUB_SESSION"))
            .as("a subscriber must not be able to make the site remember it").isNull();
        assertThat(first.getRequest().getSession(false)).isNull();

        mvc.perform(get("/feed.rss").header("If-None-Match", etag))
            .andExpect(status().isNotModified())
            .andExpect(header().string("ETag", etag));
        mvc.perform(get("/feed.rss").header("If-None-Match", "*")).andExpect(status().isNotModified());
        // The weak tag is the tag: RFC 9110 says a conditional GET compares the opaque value, W/ prefix and all.
        mvc.perform(get("/feed.rss").header("If-None-Match", etag.replace("W/", "")))
            .andExpect(status().isNotModified());
        // A different outlet has a different body, so it must not reuse the site feed's validator.
        mvc.perform(get("/feed.atom").header("If-None-Match", etag)).andExpect(status().isOk());

        create("Second", "another body", "published", List.of("a"), List.of());
        mvc.perform(get("/feed.rss").header("If-None-Match", etag))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", org.hamcrest.Matchers.not(etag)));
    }

    @Test
    @DisplayName("FR-1.5: the permanent ID does not move when the title does")
    void editingATitleLeavesTheIdentifierAlone() throws Exception {
        long id = create("A Bad Title", "body", "published", List.of(), List.of());
        String permalink = BASE + "/blog/a-bad-title";

        assertThat(rssGuids()).containsExactly(permalink);
        String atomId = tagContent(body(mvc.perform(get("/feed.atom"))), "entry", "id");
        String jsonId = json.readTree(body(mvc.perform(get("/feed.json"))))
            .get("items").get(0).get("id").asString();
        assertThat(atomId).isEqualTo(permalink);
        assertThat(jsonId).isEqualTo(permalink);

        // The slug column has no UPDATE statement anywhere in PostRepository, so the new title cannot move the URL.
        mvc.perform(write(put("/api/posts/" + id), Map.of("title", "A Tracing Primer",
                "bodyMd", "a rewritten body", "tags", List.of(), "categories", List.of())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.slug").value("a-bad-title"))
            .andExpect(jsonPath("$.title").value("A Tracing Primer"));

        assertThat(rssGuids()).containsExactly(permalink);
        assertThat(body(mvc.perform(get("/feed.atom")))).contains("<title>A Tracing Primer</title>");
        assertThat(json.readTree(body(mvc.perform(get("/feed.json"))))
            .get("items").get(0).get("id").asString()).isEqualTo(permalink);
        // And the page still answers at the URL the feed has always pointed at.
        mvc.perform(get("/blog/a-bad-title")).andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "text/html;charset=UTF-8"));
    }

    @Test
    @DisplayName("FR-1.1: a draft is invisible, and an unpublished URL is a 404 rather than a 403")
    void draftsAreAbsentFromEveryOutletAndTheirPageIsNotFound() throws Exception {
        create("Work in progress", "not ready", "draft", List.of("wip"), List.of());
        mvc.perform(get("/blog/work-in-progress")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_post"));
        for (String format : List.of("rss", "atom", "json")) {
            assertThat(body(mvc.perform(get("/feed." + format)))).doesNotContain("Work in progress");
        }

        // Past its time, a scheduled row becomes visible by the clock alone: no job flips the status.
        long soon = create("Scheduled", "later", "draft", List.of("wip"), List.of());
        db.sql("update post set status = 'scheduled', published_at = ? where id = ?")
            .param(java.sql.Timestamp.from(Instant.now().plus(Duration.ofHours(1)))).param(soon).update();
        mvc.perform(get("/feed.rss")).andExpect(status().isOk());
        assertThat(rssGuids()).isEmpty();
        db.sql("update post set published_at = ? where id = ?")
            .param(java.sql.Timestamp.from(Instant.now().minus(Duration.ofMinutes(1)))).param(soon).update();
        assertThat(rssGuids()).containsExactly(BASE + "/blog/scheduled");

        // Unlisted: the page still answers, the outlets do not carry it.
        mvc.perform(write(patch("/api/posts/" + soon + "/status"), Map.of("status", "published")))
            .andExpect(status().isOk());
        mvc.perform(write(patch("/api/posts/" + soon + "/status"), Map.of("status", "unlisted")))
            .andExpect(status().isOk());
        assertThat(rssGuids()).doesNotContain(BASE + "/blog/scheduled");
        mvc.perform(get("/blog/scheduled")).andExpect(status().isOk());
    }

    @Test
    void theStatusMachineRefusesTheTransitionsThatWouldLoseAnAuthorsWork() throws Exception {
        long id = create("Once published", "body", "published", List.of(), List.of());
        mvc.perform(write(patch("/api/posts/" + id + "/status"), Map.of("status", "scheduled")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("urn:rsshub:illegal_transition"));
        mvc.perform(write(patch("/api/posts/" + id + "/status"), Map.of("status", "bogus")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:invalid_status"));
        // "scheduled" is the one status that cannot be reached without also being told when.
        long draft = create("Still a draft", "body", "draft", List.of(), List.of());
        mvc.perform(write(patch("/api/posts/" + draft + "/status"), Map.of("status", "scheduled")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:publish_at_required"));
    }

    @Test
    @DisplayName("FR-1.1: the slug is chosen once and then no longer editable")
    void anExplicitSlugIsKeptAndACollisionIsRefused() throws Exception {
        // A slug with a path separator cannot be a permanent URL, so the editor refuses it at creation rather than
        // storing something the router can never serve.
        mvc.perform(write(post("/api/posts"), Map.of("title", "Anything", "bodyMd", "b", "slug", "kernel/tracing")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:invalid_slug"));
        mvc.perform(write(post("/api/posts"), Map.of("title", "Two", "bodyMd", "b", "slug", "has space")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:invalid_slug"));

        long id = create("Anything", "body", "draft", "explicit-slug", List.of(), List.of());
        mvc.perform(get("/blog/explicit-slug").session(session)).andExpect(status().isNotFound());
        mvc.perform(write(post("/api/posts"), Map.of("title", "Three", "bodyMd", "b", "slug", "explicit-slug")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("urn:rsshub:slug_taken"));

        // An edit that also carries a slug must not move the URL: the update path simply ignores that field.
        mvc.perform(write(put("/api/posts/" + id), Map.of("title", "Anything renamed",
                "bodyMd", "b", "slug", "should-be-ignored", "tags", List.of(), "categories", List.of())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.slug").value("explicit-slug"));
    }

    @Test
    @DisplayName("03 §6.2: the slices are real outlets, and a typo in one is a 404 not an empty feed")
    void tagAndCategorySlicesPublishToo() throws Exception {
        create("Tracing part one", "b1", "published", List.of("Observability"), List.of("Deep dives"));
        create("Gardening", "b2", "published", List.of("Home"), List.of());

        String byTag = body(mvc.perform(get("/tag/observability.rss")));
        assertThat(byTag).contains("Tracing part one").doesNotContain("Gardening")
            .contains("<title>Observability · Observability Notes</title>");
        assertThat(body(mvc.perform(get("/category/deep-dives.atom")))).contains("Tracing part one")
            .doesNotContain("Gardening");
        assertThat(json.readTree(body(mvc.perform(get("/tag/home.json")))).get("items").size()).isEqualTo(1);

        mvc.perform(get("/tag/no-such-tag.rss")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_tag"));
        mvc.perform(get("/category/no-such-category.json")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_category"));
        // An unknown suffix is a URL this site does not have, and answering 404 rather than 400 keeps the
        // "this outlet is empty" signal the same as "no such outlet".
        mvc.perform(get("/feed.opml")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_format"));
    }

    @Test
    @DisplayName("FR-1.3: the discovery block a crawler reads is in the HTML, not behind a JavaScript bundle")
    void publicPagesAdvertiseEveryOutletTheyPublish() throws Exception {
        create("Discoverable", "a body", "published", List.of("Observability Week"), List.of());

        String page = body(mvc.perform(get("/blog/discoverable")));
        assertThat(page).contains("<link rel=\"alternate\"")
            .contains(BASE + "/feed.rss").contains(BASE + "/feed.atom").contains(BASE + "/feed.json")
            // The article's own granularity as well as the site's.
            .contains(BASE + "/tag/observability-week.rss")
            .contains("application/rss+xml").contains("application/atom+xml")
            .contains("application/feed+json")
            .doesNotContain("rel=\"hub\"");
        // A tag link titled after the site would leave six identical labels on the page and the reader could not pick
        // the tag feed out of the site feed; the label is the term's title, the href stays the derived slug.
        assertThat(page).contains("<link rel=\"alternate\" href=\"" + BASE + "/feed.rss\""
            + " type=\"application/rss+xml;charset=UTF-8\" title=\"Observability Notes (rss)\">");
        assertThat(page).contains("<link rel=\"alternate\" href=\"" + BASE + "/tag/observability-week.rss\""
            + " type=\"application/rss+xml;charset=UTF-8\" title=\"Observability Week (rss)\">");
        assertThat(page).contains("<h1>Discoverable</h1>").contains("<p>a body</p>")
            .contains("href=\"" + BASE + "/tag/observability-week\"");

        String list = body(mvc.perform(get("/blog")));
        assertThat(list).contains("Discoverable").contains(BASE + "/blog/discoverable")
            .contains("<link rel=\"alternate\"");
        // The site root is the same public list: no SPA bundle has to run for it to exist.
        assertThat(body(mvc.perform(get("/")))).contains("Discoverable");
    }

    @Test
    void aBodyWrittenInMarkdownArrivesAsHtmlInTheOutletAndThePage() throws Exception {
        create("Markdown", """
            ## A table

            | a | b |
            | - | - |
            | 1 | 2 |

            <script>alert(1)</script>
            """, "published", List.of(), List.of());

        String rss = body(mvc.perform(get("/feed.rss")));
        assertThat(rss).contains("<content:encoded><![CDATA[<h2>A table</h2>")
            .contains("<table>")
            // Whitelisted on the way in, so neither the page nor the outlet has to re-clean it.
            .doesNotContain("<script");
        assertThat(body(mvc.perform(get("/blog/markdown")))).contains("<h2>A table</h2>").doesNotContain("<script");
    }

    @Test
    void anEmptySiteStillPublishesDocumentsThatParse() throws Exception {
        // "No posts yet" is a real state of a personal site; three empty but valid documents is the honest answer.
        for (String format : List.of("rss", "atom", "json")) {
            mvc.perform(get("/feed." + format)).andExpect(status().isOk());
        }
        assertThat(json.readTree(body(mvc.perform(get("/feed.json")))).get("items").size()).isZero();
        assertThat(body(mvc.perform(get("/feed.rss")))).contains("<description>Notes about kernels, tracing")
            .doesNotContain("<item>");
        assertThat(body(mvc.perform(get("/blog")))).contains("Observability Notes");
    }

    @Test
    void theJsonOutletDegradesBodiesRatherThanTruncatingBytes() throws Exception {
        // 03 §6.3 rule 7: the budget is 100 KB of document. Three fat posts blow straight through it.
        String fat = "x".repeat(60 * 1024);
        create("Fat one", fat, "published", List.of(), List.of());
        create("Fat two", fat, "published", List.of(), List.of());
        create("Fat three", fat, "published", List.of(), List.of());

        MvcResult result = mvc.perform(get("/feed.json")).andExpect(status().isOk()).andReturn();
        byte[] raw = result.getResponse().getContentAsByteArray();
        assertThat(raw.length).as("the cap is a real bound, not an attempt").isLessThan(100 * 1024);

        JsonNode items = json.readTree(new String(raw, StandardCharsets.UTF_8)).get("items");
        assertThat(items).hasSize(3);
        assertThat(items.get(0).has("content_html")).as("the newest item keeps its body").isTrue();
        for (int i = 1; i < items.size(); i++) {
            assertThat(items.get(i).has("content_html")).as("item %s shipped as a summary only", i).isFalse();
            assertThat(items.get(i).get("title").asString()).as("never dropped, only shrunk").isNotBlank();
        }
        // The same three posts through RSS are not capped at all — the rule is a JSON Feed one.
        assertThat(body(mvc.perform(get("/feed.rss")))).contains("Fat three");
    }

    @Test
    @DisplayName("the editor's own list, and the term pickers it feeds")
    void adminSeesEverythingIncludingDrafts() throws Exception {
        create("Visible draft", "d", "draft", List.of("Ebpf"), List.of("Notes"));
        long published = create("Already out", "p", "published", List.of("Ebpf"), List.of());

        // published_at desc nulls last: the published post floats to the top and the draft falls to the bottom,
        // which is the same ordering the public side uses with drafts removed entirely.
        mvc.perform(get("/api/posts").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].status").value("published"))
            .andExpect(jsonPath("$[0].id").value(published))
            .andExpect(jsonPath("$[1].status").value("draft"))
            // The editor needs the body for its preview, which is why this list is not the public one.
            .andExpect(jsonPath("$[1].bodyMd").value("d"))
            .andExpect(jsonPath("$[1].url").value(BASE + "/blog/visible-draft"));
        mvc.perform(get("/api/posts?status=published").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(published));

        mvc.perform(get("/api/posts/terms").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.tags[0].slug").value("ebpf"))
            .andExpect(jsonPath("$.tags[0].title").value("Ebpf"))
            .andExpect(jsonPath("$.categories[0].slug").value("notes"));

        mvc.perform(write(delete("/api/posts/" + published))).andExpect(status().isNoContent());
        assertThat(rssGuids()).isEmpty();
        mvc.perform(get("/api/posts/" + published).session(session))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_post"));
    }

    @Test
    void thePublicSideIsAnonymousAndTheEditorSideIsNot() throws Exception {
        mvc.perform(get("/feed.rss")).andExpect(status().isOk());
        mvc.perform(get("/blog")).andExpect(status().isOk());
        mvc.perform(get("/api/posts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/posts/terms")).andExpect(status().isUnauthorized());
        // A cross-site form has neither the session nor the CSRF header, so it is refused and nothing is written.
        mvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"x\",\"bodyMd\":\"y\"}"))
            .andExpect(status().is4xxClientError());
        assertThat(db.sql("select count(*) from post").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("TC-PST-34/35: the whole status matrix is allowed or refused on purpose, pair by pair")
    void everyPairOfStatusesHasExactlyOneAnswer() throws Exception {
        // Restated here rather than read back from PostService: if the table drifts, this test has to disagree with it.
        Map<String, List<String>> legal = Map.of(
            "draft", List.of("scheduled", "published", "archived"),
            "scheduled", List.of("draft", "published", "archived"),
            "published", List.of("draft", "unlisted", "archived"),
            "unlisted", List.of("published", "archived"),
            "archived", List.of("draft", "published"));
        List<String> all = List.of("draft", "scheduled", "published", "unlisted", "archived");

        int pairs = 0;
        for (String from : all) {
            for (String to : all) {
                if (from.equals(to)) {
                    continue;
                }
                pairs++;
                // A fresh post per pair: a legal move changes the row, so reusing one post would test the matrix
                // after the previous move rather than the state the case names.
                long id = seed(from, from + "-to-" + to);
                Map<String, Object> payload = new java.util.HashMap<>();
                payload.put("status", to);
                if ("scheduled".equals(to)) {
                    payload.put("publishedAt", Instant.now().plus(Duration.ofDays(1)).toString());
                }
                ResultActions result = mvc.perform(write(patch("/api/posts/" + id + "/status"), payload));
                if (legal.get(from).contains(to)) {
                    result.andExpect(status().isOk()).andExpect(jsonPath("$.status").value(to));
                } else {
                    result.andExpect(status().isConflict())
                        .andExpect(jsonPath("$.type").value("urn:rsshub:illegal_transition"))
                        .andExpect(jsonPath("$.detail").value(from + " cannot become " + to));
                }
            }
        }
        assertThat(pairs).as("five statuses, no self-transitions").isEqualTo(20);
    }

    @Test
    @DisplayName("TC-PST-36/38, TC-API-02: a no-op status change is accepted, an empty request never is")
    void redundantStatusChangesAreHarmlessAndEmptyBodiesAreRefused() throws Exception {
        long id = create("Redundant", "body", "published", List.of(), List.of());
        String before = json.readTree(body(mvc.perform(get("/api/posts/" + id).session(session))))
            .get("updatedAt").asString();

        mvc.perform(write(patch("/api/posts/" + id + "/status"), Map.of("status", "published")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("published"));
        // The early return means the row is not touched at all, so the timestamps cannot drift.
        assertThat(json.readTree(body(mvc.perform(get("/api/posts/" + id).session(session)))).get("updatedAt").asString())
            .isEqualTo(before);

        mvc.perform(write(patch("/api/posts/" + id + "/status"), Map.of())).andExpect(status().isBadRequest());
        mvc.perform(write(patch("/api/posts/" + id + "/status"), Map.of("status", "  ")))
            .andExpect(status().isBadRequest());

        // Nothing half-written: both required fields are validated before the service is entered.
        long count = db.sql("select count(*) from post").query(Long.class).single();
        mvc.perform(write(post("/api/posts"), Map.of("title", "", "bodyMd", "b")))
            .andExpect(status().isBadRequest());
        mvc.perform(write(post("/api/posts"), Map.of("title", "t")))
            .andExpect(status().isBadRequest());
        assertThat(db.sql("select count(*) from post").query(Long.class).single()).isEqualTo(count);
    }

    @Test
    @DisplayName("TC-PUB-14: two outlets with the same content must not share a validator")
    void eachFormatCarriesItsOwnValidator() throws Exception {
        create("Distinct", "a body", "published", List.of(), List.of());
        Map<String, String> tags = new java.util.HashMap<>();
        for (String format : List.of("rss", "atom", "json")) {
            tags.put(format, mvc.perform(get("/feed." + format)).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag"));
        }
        // The seed holds the outlet path and the format for exactly this reason: a proxy that confuses the two would
        // otherwise serve XML to a JSON Feed client and both would believe the validator proved it.
        assertThat(tags.values()).doesNotHaveDuplicates();
        for (String format : List.of("rss", "atom", "json")) {
            String other = tags.get("rss".equals(format) ? "atom" : "rss");
            mvc.perform(get("/feed." + format).header("If-None-Match", other)).andExpect(status().isOk());
        }
    }

    /** A post seeded directly into a status, with a slug short enough to read in a failure message. */
    private long seed(String status, String name) throws Exception {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("title", name);
        payload.put("bodyMd", "body");
        payload.put("status", status);
        payload.put("slug", name.replace(' ', '-').toLowerCase(Locale.ROOT));
        if ("scheduled".equals(status)) {
            payload.put("publishedAt", Instant.now().minus(Duration.ofDays(1)).toString());
        }
        return json.readTree(body(mvc.perform(write(post("/api/posts"), payload))
            .andExpect(status().isCreated()))).get("id").asLong();
    }

    // ---------------------------------------------------------------- helpers

    private long create(String title, String bodyMd, String status, List<String> tags, List<String> categories)
        throws Exception {
        return create(title, bodyMd, status, null, tags, categories);
    }

    private long create(String title, String bodyMd, String status, String slug, List<String> tags,
        List<String> categories) throws Exception {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("title", title);
        payload.put("bodyMd", bodyMd);
        payload.put("status", status);
        payload.put("tags", tags);
        payload.put("categories", categories);
        if (slug != null) {
            payload.put("slug", slug);
        }
        return json.readTree(body(mvc.perform(write(post("/api/posts"), payload))
            .andExpect(status().isCreated()))).get("id").asLong();
    }

    /** The guid set of the site feed, which is the same string as the item's permanent URL. */
    private List<String> rssGuids() throws Exception {
        String rss = body(mvc.perform(get("/feed.rss")));
        List<String> guids = new java.util.ArrayList<>();
        for (String piece : rss.split("<guid isPermaLink=\"true\">")) {
            int end = piece.indexOf("<");
            if (end > 0) {
                guids.add(piece.substring(0, end));
            }
        }
        return guids;
    }

    /** A crude but sufficient reader: the first {@code child} inside the first {@code parent} element. */
    private static String tagContent(String xml, String parent, String child) {
        int from = xml.indexOf("<" + parent + ">");
        String body = xml.substring(from);
        int start = body.indexOf("<" + child + ">") + child.length() + 2;
        return body.substring(start, body.indexOf("</" + child + ">", start));
    }

    private String body(ResultActions actions) throws Exception {
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
