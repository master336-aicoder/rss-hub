package com.rsshub.adminapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rsshub.common.ApiException;
import com.rsshub.feedfetch.LocalFeedServer;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * M1d's acceptance gate: the buttons an operator presses, end to end over HTTP.
 *
 * <p>Discovery and adding really fetch, so the test publishes from {@link LocalFeedServer} on loopback — which is
 * also why the SSRF allow-list is widened here instead of trusting the default deny. The dispatcher is switched off
 * ({@code rsshub.fetch.enabled=false}) because it would claim the very rows these tests assert on; the queue is
 * therefore checked in SQL, which is exactly what "the button armed this feed" means.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:adminapi;DB_CLOSE_DELAY=-1",
    "rsshub.operator.username=admin",
    "rsshub.operator.password=a-test-only-password",
    "rsshub.fetch.enabled=false",
    "rsshub.ssrf.allow-private-prefixes[0]=127.0.0.1"
})
@AutoConfigureMockMvc
class InboxApiTest {

    private static final String PASSWORD = "a-test-only-password";
    private static final Map<String, String> RSS_HEADERS = Map.of("Content-Type", "application/rss+xml");
    private static final Map<String, String> HTML_HEADERS = Map.of("Content-Type", "text/html");

    private static final String RSS = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel><title>Loopback Weekly</title>
        <link>http://127.0.0.1/blog</link><description>test feed</description>
        <item><title>First</title><link>http://127.0.0.1/blog/1</link><guid>loop-1</guid>
        <pubDate>Sat, 17 Oct 2026 09:00:00 +0800</pubDate>
        <description>the first body</description></item>
        </channel></rss>
        """;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient db;

    @Autowired
    ObjectMapper json;

    private LocalFeedServer server;
    private MockHttpSession session;

    @BeforeEach
    void publishAndLogin() throws Exception {
        // The context and the in-memory database are shared by every test in the class, so each one starts from an
        // empty inbox. Children first: the FKs allow the cascade, they do not require it.
        db.sql("delete from entry_revision").update();
        db.sql("delete from media_asset").update();
        db.sql("delete from notification_log").update();
        db.sql("delete from entry").update();
        db.sql("delete from raw_response").update();
        db.sql("delete from scheduled_task").update();
        db.sql("delete from rule").update();
        db.sql("delete from feed").update();

        server = new LocalFeedServer();
        server.setDefault(LocalFeedServer.Scenario.ok(RSS, RSS_HEADERS));
        Cookie csrf = csrf();
        session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session)
                .param("username", "admin").param("password", PASSWORD)
                .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
            .andExpect(status().isOk());
    }

    @AfterEach
    void stopPublisher() {
        server.close();
    }

    @Test
    void addingASubscriptionResolvesItAndArmsAFetch() throws Exception {
        MvcResult created = mvc.perform(write(post("/api/feeds"),
                Map.of("url", server.url("/feed"), "group", "Tech")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("active"))
            .andExpect(jsonPath("$.groupName").value("Tech"))
            .andExpect(jsonPath("$.canonicalUrl").value(server.url("/feed")))
            .andReturn();

        long id = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        assertThat(taskState(id)).isEqualTo("pending");

        mvc.perform(get("/api/feeds").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].nextFetchAt").exists())
            .andExpect(jsonPath("$[0].entryCount").value(0))
            .andExpect(jsonPath("$[0].unreadCount").value(0));
    }

    @Test
    void theSameFeedCannotBeAddedTwice() throws Exception {
        add(server.url("/feed"));
        mvc.perform(write(post("/api/feeds"), Map.of("url", server.url("/feed"))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("urn:rsshub:already_subscribed"));
    }

    @Test
    void aPageOfferingSeveralFeedsIsListedRatherThanGuessedAt() throws Exception {
        server.on("/blog", LocalFeedServer.Scenario.ok("""
            <!doctype html><html><head>
            <link rel="alternate" type="application/rss+xml" title="RSS" href="/blog/rss.xml">
            <link rel="alternate" type="application/atom+xml" title="Atom" href="/blog/atom.xml">
            <link rel="stylesheet" href="/style.css">
            </head><body>hi</body></html>
            """, HTML_HEADERS));

        JsonNode body = read(mvc.perform(write(post("/api/feeds/discover"), Map.of("url", server.url("/blog"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.candidates.length()").value(2))
            .andReturn());

        // 03 §4.4 step 1: Atom outranks RSS, so the default choice is the Atom one while both stay listed.
        assertThat(body.get("feedUrl").asString()).isEqualTo(server.url("/blog/atom.xml"));
        assertThat(body.get("candidates").get(0).get("url").asString()).isEqualTo(server.url("/blog/rss.xml"));
        assertThat(body.get("candidates").get(0).get("title").asString()).isEqualTo("RSS");
    }

    @Test
    void discoveryTriesTheCommonPathsBeforeGivingUp() throws Exception {
        server.setDefault(LocalFeedServer.Scenario.ok(
            "<!doctype html><html><head><title>no links</title></head><body>hi</body></html>", HTML_HEADERS));
        server.on("/rss.xml", LocalFeedServer.Scenario.ok(RSS, RSS_HEADERS));

        JsonNode body = read(mvc.perform(write(post("/api/feeds/discover"), Map.of("url", server.url("/blog"))))
            .andExpect(status().isOk())
            .andReturn());

        assertThat(body.get("feedUrl").asString()).isEqualTo(server.url("/rss.xml"));
    }

    @Test
    void aPageThatIsNotAFeedIsRefusedAndNothingIsWritten() throws Exception {
        server.setDefault(LocalFeedServer.Scenario.ok(
            "<!doctype html><html><body>a plain page</body></html>", HTML_HEADERS));

        mvc.perform(write(post("/api/feeds"), Map.of("url", server.url("/nowhere"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:no_feed_found"));

        assertThat(db.sql("select count(*) from feed").query(Long.class).single()).isZero();
    }

    @Test
    void aMetadataAddressIsRefusedBeforeAnyRequestIsMade() throws Exception {
        mvc.perform(write(post("/api/feeds"), Map.of("url", "http://169.254.169.254/latest/meta-data")))
            .andExpect(status().isBadRequest())
            // The reason never comes back: the response may not confirm that an internal address exists.
            .andExpect(jsonPath("$.detail").value("Target address is not allowed"));
    }

    @Test
    void pausingCancelsTheTaskAndReactivatingArmsItAgain() throws Exception {
        long id = add(server.url("/feed"));

        mvc.perform(write(patch("/api/feeds/" + id), Map.of("state", "paused")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("paused"));
        assertThat(taskState(id)).isEqualTo("cancelled");

        mvc.perform(write(patch("/api/feeds/" + id), Map.of("state", "active", "intervalMinutes", 30)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fetchIntervalMinutes").value(30));
        assertThat(taskState(id)).isEqualTo("pending");

        mvc.perform(write(patch("/api/feeds/" + id), Map.of("state", "error")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:bad_state"));
    }

    @Test
    void refreshPullsForwardInsteadOfStackingUpWork() throws Exception {
        long id = add(server.url("/feed"));
        db.sql("update scheduled_task set fire_at = ? where kind = 'fetch' and ref_id = ?")
            .params(java.sql.Timestamp.from(Instant.now().plus(Duration.ofHours(1))), id)
            .update();

        mvc.perform(write(post("/api/feeds/" + id + "/refresh"), Map.of()))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.queued").value(true));

        assertThat(db.sql("select count(*) from scheduled_task where kind = 'fetch' and ref_id = ?")
            .param(id).query(Long.class).single()).isEqualTo(1);
        assertThat(db.sql("select fire_at from scheduled_task where kind = 'fetch' and ref_id = ?")
            .param(id).query(java.sql.Timestamp.class).single())
            .isBefore(java.sql.Timestamp.from(Instant.now().plus(Duration.ofMinutes(1))));
    }

    @Test
    void refreshingAGoneFeedIsRefusedRatherThanPretending() throws Exception {
        long id = add(server.url("/feed"));
        db.sql("update feed set state = 'gone' where id = ?").param(id).update();

        mvc.perform(write(post("/api/feeds/" + id + "/refresh"), Map.of()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("urn:rsshub:not_schedulable"));
    }

    @Test
    void deletingTakesTheEntriesAndTheQueueRowWithIt() throws Exception {
        long id = add(server.url("/feed"));
        insertEntry(id, "gone-with-it");

        mvc.perform(write(delete("/api/feeds/" + id), null)).andExpect(status().isNoContent());

        assertThat(db.sql("select count(*) from feed").query(Long.class).single()).isZero();
        assertThat(db.sql("select count(*) from entry").query(Long.class).single()).isZero();
        assertThat(db.sql("select count(*) from scheduled_task").query(Long.class).single()).isZero();
        mvc.perform(get("/api/feeds/" + id).session(session))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:feed_not_found"));
    }

    @Test
    void unreadExcludesTheBaselineAndStarringRedoesTheCounts() throws Exception {
        long id = add(server.url("/feed"));
        insertEntry(id, "history", "unread", true);
        insertEntry(id, "fresh", "unread", false);
        insertEntry(id, "read-one", "read", false);

        mvc.perform(get("/api/entries?state=unread").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].title").value("fresh"))
            // The counters ride along with every page, so the sidebar badges cannot drift from the list.
            .andExpect(jsonPath("$.counters.unread").value(1))
            .andExpect(jsonPath("$.counters.total").value(3))
            // A list row is a row: the body belongs to the detail endpoint and nowhere else.
            .andExpect(jsonPath("$.items[0].contentHtml").doesNotExist());

        long fresh = entryId(id, "fresh");
        mvc.perform(write(post("/api/entries/state"), Map.of("ids", List.of(fresh), "starred", true)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.applied").value(1));

        mvc.perform(get("/api/entries?state=starred").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value(fresh))
            .andExpect(jsonPath("$.counters.starred").value(1));

        mvc.perform(get("/api/entries?state=bogus").session(session))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:bad_state"));
        mvc.perform(get("/api/entries?cursor=not-a-cursor").session(session))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:bad_cursor"));
        mvc.perform(write(post("/api/entries/state"), Map.of("ids", List.of(fresh))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:nothing_to_set"));
    }

    @Test
    void pagingUsesACursorAndNeverRepeatsARow() throws Exception {
        long id = add(server.url("/feed"));
        for (int day = 11; day <= 15; day++) {
            insertEntry(id, "page-" + (day - 10), "unread", false, "2026-10-" + day + "T00:00:00Z");
        }

        JsonNode first = read(mvc.perform(get("/api/entries?state=unread&limit=2").session(session))
            .andExpect(status().isOk()).andReturn());
        assertThat(titles(first)).containsExactly("page-5", "page-4");
        String cursor = first.get("nextCursor").asString();

        JsonNode second = read(mvc.perform(get("/api/entries?state=unread&limit=2&cursor=" + cursor)
            .session(session)).andExpect(status().isOk()).andReturn());
        assertThat(titles(second)).containsExactly("page-3", "page-2");

        JsonNode third = read(mvc.perform(get("/api/entries?state=unread&limit=2&cursor="
            + second.get("nextCursor").asString()).session(session))
            .andExpect(status().isOk()).andReturn());
        assertThat(titles(third)).containsExactly("page-1");
        // non_null inclusion means the last page has no nextCursor key at all, not a null one.
        assertThat(third.has("nextCursor")).as("the end of the list must not hand out a cursor").isFalse();
    }

    @Test
    void searchAndFeedFiltersNarrowTheSameList() throws Exception {
        long id = add(server.url("/feed"));
        insertEntry(id, "kernel-notes");
        long other = add(server.url("/other"));
        insertEntry(other, "garden-notes");

        mvc.perform(get("/api/entries?state=all&q=kernel").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].title").value("kernel-notes"));

        mvc.perform(get("/api/entries?state=all&feed=" + other).session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].feedId").value(other));

        // Case is the operator's problem, not the column's: the query is lower-cased before the LIKE.
        mvc.perform(get("/api/entries?state=all&q=GARDEN").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    @DisplayName("FR-2.4: ?alerted narrows to exactly the rows that carry the rule badge")
    void alertedFilterIsTheSameFactAsTheRowBadge() throws Exception {
        long id = add(server.url("/feed"));
        insertEntry(id, "oom-report");
        insertEntry(id, "routine-update");
        long ruleId = rule("生产 OOM");
        markAlerted(ruleId, entryId(id, "oom-report"));

        JsonNode hits = page(get("/api/entries?state=all").param("alerted", "true"));
        assertThat(titles(hits)).containsExactly("oom-report");
        // Whatever the filter hands back must be markable by eye, and vice versa: one table answers both.
        assertThat(hits.get("items").get(0).get("alertedBy").get(0).asString()).isEqualTo("生产 OOM");

        assertThat(pageOfTitles(get("/api/entries?state=all").param("alerted", "false")))
            .containsExactly("routine-update");

        // Absent means "do not narrow" — the inbox's default view is a tri-state, not a boolean.
        assertThat(pageOfTitles(get("/api/entries?state=all")))
            .containsExactlyInAnyOrder("oom-report", "routine-update");

        mvc.perform(get("/api/entries?state=all&alerted=maybe").session(session))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:bad_alerted"));

        // Deleting the rule nulls the log's rule_id but keeps the row, so the entry stays alerted and stays
        // filterable: the reason can go away without the fact that it happened going away with it.
        db.sql("delete from rule").update();
        assertThat(pageOfTitles(get("/api/entries?state=all").param("alerted", "true")))
            .containsExactly("oom-report");
    }

    @Test
    @DisplayName("FR-2.4: ?category matches one whole element of the JSON array, never a substring")
    void categoryFilterMatchesWholeElements() throws Exception {
        long id = add(server.url("/feed"));
        insertCategorised(id, "dev-post", "[\"Dev\",\"Ops\"]");
        insertCategorised(id, "ops-post", "[\"Ops\"]");
        insertCategorised(id, "career-post", "[\"Developer\"]");
        insertCategorised(id, "percent-post", "[\"50%\"]");
        insertCategorised(id, "loose-percent", "[\"50 percent\"]");
        insertCategorised(id, "snake-post", "[\"a_b\"]");
        insertCategorised(id, "wide-post", "[\"axb\"]");
        insertCategorised(id, "quoted-post", "[\"a\\\"b\"]");

        // The closing quote is part of the token, so a prefix of a longer tag cannot leak in…
        assertThat(categorised("Dev")).containsExactly("dev-post");
        // …and any element counts, not just the first.
        assertThat(categorised("Ops")).containsExactlyInAnyOrder("dev-post", "ops-post");
        assertThat(categorised("Developer")).containsExactly("career-post");
        // LIKE's wildcards are escaped, which is the only reason a tag named "50%" or "a_b" is usable at all.
        assertThat(categorised("50%")).containsExactly("percent-post");
        assertThat(categorised("a_b")).containsExactly("snake-post");
        // A quote inside the name goes through the same encoder that wrote the column, escapes and all.
        assertThat(categorised("a\"b")).containsExactly("quoted-post");
        // The stored spelling is the key: the reader pane sends the tag it read from the row, never a guess.
        assertThat(categorised("dev")).isEmpty();
        // Blank is not a tag, so it must not exclude every row.
        assertThat(categorised("")).hasSize(8);

        // Composes with the rest of the filter, and with paging: a narrowed list still pages.
        assertThat(categorisedQuery("Dev", "post")).containsExactly("dev-post");
        mvc.perform(get("/api/entries?state=all&category=Ops&limit=1").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.nextCursor").isNotEmpty());

        // The tab counters are global by design (FR-2.4's badges answer "how much is there", not "what is shown").
        mvc.perform(get("/api/entries?state=all&category=Dev").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.counters.total").value(8))
            .andExpect(jsonPath("$.counters.unread").value(8));
    }

    @Test
    void detailCarriesTheBodyAndTheRevisionHistory() throws Exception {
        long id = add(server.url("/feed"));
        // Nothing has fetched this feed (the dispatcher is off), so its title only exists once the test gives it one
        // — which is also the state a real subscription is in after its first successful round.
        db.sql("update feed set title = 'Loopback Weekly' where id = ?").param(id).update();
        insertEntry(id, "with-body");
        long entryId = entryId(id, "with-body");
        db.sql("insert into entry_revision (entry_id, revision, title, content_hash, recorded_at) "
                + "values (?, 2, 'with-body v2', 'hash', ?)")
            .params(entryId, java.sql.Timestamp.from(Instant.now()))
            .update();

        mvc.perform(get("/api/entries/" + entryId).session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contentHtml").value("<p>body of with-body</p>"))
            .andExpect(jsonPath("$.revisions[0].revision").value(2))
            .andExpect(jsonPath("$.revisions[0].title").value("with-body v2"))
            .andExpect(jsonPath("$.authors[0].name").value("Ann"))
            .andExpect(jsonPath("$.feedTitle").value("Loopback Weekly"))
            .andExpect(jsonPath("$.guidSource").value("guid"))
            .andExpect(jsonPath("$.stableId").value(true));

        mvc.perform(get("/api/entries/999999").session(session))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:entry_not_found"));
    }

    @Test
    void markFeedReadLeavesStarredEntriesAlone() throws Exception {
        long id = add(server.url("/feed"));
        insertEntry(id, "a", "unread", false);
        insertEntry(id, "b", "unread", false);

        mvc.perform(write(post("/api/feeds/" + id + "/read"), Map.of()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.marked").value(2));

        assertThat(db.sql("select read_state from entry where feed_id = ? order by guid")
            .param(id).query(String.class).list()).containsExactly("read", "read");
    }

    @Test
    void opmlExportsGroupsAndReimportsThem() throws Exception {
        add(server.url("/feed"), "Tech");
        long other = add(server.url("/other"));

        MvcResult exported = mvc.perform(get("/api/opml").session(session)).andExpect(status().isOk()).andReturn();
        String xml = exported.getResponse().getContentAsString();
        assertThat(exported.getResponse().getContentType()).startsWith("application/opml");
        assertThat(xml).contains("version=\"2.0\"").contains(server.url("/feed"));
        // The group the operator set has to survive the trip, or "restore it in Miniflux" restores a flat list.
        assertThat(xml).contains("text=\"Tech\"");

        db.sql("delete from feed where id = ?").param(other).update();
        mvc.perform(write(post("/api/opml"), null, xml))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.imported").value(1))
            .andExpect(jsonPath("$.skipped").value(1));

        mvc.perform(write(post("/api/opml"), null,
            "<opml version=\"2.0\"><body><outline title=\"folder\"/></body></opml>"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:no_outlines"));
    }

    @Test
    void importKeepsNestingAndRefusesWhatItCannotTrust() throws Exception {
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <opml version="2.0"><body>
              <outline text="News" title="News">
                <outline type="rss" text="Loopback" xmlUrl="%1$s/a" htmlUrl="http://127.0.0.1/a"/>
                <outline type="rss" text="Meta" xmlUrl="http://169.254.169.254/feed"/>
              </outline>
              <outline type="rss" text="Flat" xmlUrl="%1$s/b"/>
            </body></opml>
            """.formatted(server.url(""));

        mvc.perform(write(post("/api/opml"), null, xml))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.imported").value(2))
            .andExpect(jsonPath("$.skipped").value(1));

        assertThat(db.sql("select group_name from feed where canonical_url = ?")
            .param(server.url("/a")).query(String.class).single()).isEqualTo("News");
        // A top-level outline has no folder: the row must exist with no group, which single() cannot express as
        // null, so the sentinel makes "absent" and "not imported" different answers.
        assertThat(db.sql("select coalesce(group_name, '<none>') from feed where canonical_url = ?")
            .param(server.url("/b")).query(String.class).single()).isEqualTo("<none>");
        assertThat(db.sql("select count(*) from scheduled_task where kind = 'fetch'")
            .query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void healthViewPutsBrokenSourcesFirst() throws Exception {
        long quiet = add(server.url("/feed"));
        long broken = add(server.url("/broken"));
        db.sql("update feed set state = 'error', fail_count = 3, last_error = 'connect_failed', "
                + "last_http_status = 500 where id = ?").param(broken).update();
        db.sql("update feed set last_change_at = ? where id = ?")
            .params(java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(5))), quiet)
            .update();

        JsonNode body = read(mvc.perform(get("/api/health/feeds").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.summary.total").value(2))
            .andExpect(jsonPath("$.summary.failing").value(1))
            .andExpect(jsonPath("$.summary.quiet").value(1))
            .andReturn());

        JsonNode first = body.get("feeds").get(0);
        assertThat(first.get("id").asLong()).isEqualTo(broken);
        assertThat(first.get("failCount").asInt()).isEqualTo(3);
        assertThat(first.get("lastError").asString()).isEqualTo("connect_failed");
        assertThat(first.has("nextFetchAt")).as("an error feed is still on the schedule").isTrue();
        assertThat(body.get("feeds").get(1).get("quietForSeconds").asLong())
            .isGreaterThan(Duration.ofDays(4).toSeconds());
    }

    @Test
    void anonymousCallsStayRejected() throws Exception {
        mvc.perform(get("/api/entries")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/health/feeds")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/opml")).andExpect(status().isUnauthorized());
        // A cross-site form has neither the session nor the CSRF header, so this is 401 or 403 and never a write.
        mvc.perform(post("/api/feeds").contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + server.url("/feed") + "\"}"))
            .andExpect(status().is4xxClientError());
        assertThat(db.sql("select count(*) from feed").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("TC-RDR-06/07/08/09: a state write is refused before it can touch a single row")
    void stateWritesRefuseAnEmptyBatchAnUnknownFlagAndAnAbsurdSize() throws Exception {
        long id = add(server.url("/feed"));
        insertEntry(id, "one", "unread", false);
        long entryId = entryId(id, "one");

        mvc.perform(write(post("/api/entries/state"), Map.of("ids", List.of())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:no_ids"));
        // "deleted" looks plausible and is not a state this product has; the reader's flags are unread/read/dismissed.
        mvc.perform(write(post("/api/entries/state"), Map.of("ids", List.of(entryId), "readState", "deleted")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:bad_read_state"));
        mvc.perform(write(post("/api/entries/state"), Map.of("ids", List.of(1, 2, 3))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:nothing_to_set"));

        List<Long> tooMany = java.util.stream.IntStream.range(0, 501).mapToObj(i -> entryId).toList();
        mvc.perform(write(post("/api/entries/state"), Map.of("ids", tooMany, "starred", true)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:too_many_ids"));
        // The refusal has to happen before the statement is built, so a rejected batch leaves nothing behind.
        assertThat(db.sql("select count(*) from entry where starred = TRUE").query(Long.class).single()).isZero();

        // 500 is the ceiling, not an off-by-one wall: the same batch one id smaller must go through.
        mvc.perform(write(post("/api/entries/state"),
                Map.of("ids", java.util.stream.IntStream.range(0, 500).mapToObj(i -> entryId).toList(),
                    "starred", true)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.applied").value(1));
    }

    @Test
    @DisplayName("TC-INB-38: since is an instant or a 400, never a silently ignored filter")
    void sinceBoundsTheListByPublishedOrFetchedTime() throws Exception {
        long id = add(server.url("/feed"));
        insertEntry(id, "old", "unread", false, "2026-10-01T00:00:00Z");
        insertEntry(id, "new", "unread", false, "2026-10-20T00:00:00Z");

        mvc.perform(get("/api/entries").param("state", "unread").param("since", "2026-10-10T00:00:00Z")
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].title").value("new"));
        mvc.perform(get("/api/entries").param("state", "unread").param("since", "last tuesday").session(session))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:bad_since"));
    }

    @Test
    @DisplayName("TC-INB-12: the search box matches literals, so % and _ cannot widen a result set")
    void wildcardsInTheQueryAreCharactersRatherThanOperators() throws Exception {
        long id = add(server.url("/feed"));
        insertEntry(id, "growth-50%-milestone");
        insertEntry(id, "a_b_test");
        insertEntry(id, "plain-title");

        // Sent as parameters, not inside a URI template: the point is the value, and a "%" in a template is a
        // placeholder waiting to be re-encoded.
        mvc.perform(get("/api/entries").param("state", "all").param("q", "50%").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].title").value("growth-50%-milestone"));
        mvc.perform(get("/api/entries").param("state", "all").param("q", "a_b").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].title").value("a_b_test"));
        // A bare "%" used to be the row-count amplifier: unescaped it matches every entry in the database.
        mvc.perform(get("/api/entries").param("state", "all").param("q", "%").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].title").value("growth-50%-milestone"));
        mvc.perform(get("/api/entries").param("state", "all").param("q", "_").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    @DisplayName("TC-SUB-10/11/13: what an operator pastes is completed or refused, never guessed at")
    void aPastedAddressIsNormalisedBeforeItBecomesASubscription() {
        assertThat(SubscriptionService.normalise("example.com/rss")).isEqualTo("https://example.com/rss");
        // The fragment never selects a different feed; leaving it in splits one subscription into two rows.
        assertThat(SubscriptionService.normalise("http://127.0.0.1/feed.xml#week-12"))
            .isEqualTo("http://127.0.0.1/feed.xml");
        assertThat(SubscriptionService.normalise(" https://example.com/feed ")).isEqualTo("https://example.com/feed");

        for (String refused : List.of("file:///etc/passwd", "gopher://x/y", "ftp://example.com/feed", "jar:file:x!y")) {
            assertThatThrownBy(() -> SubscriptionService.normalise(refused))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("scheme_not_allowed");
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        }
        assertThatThrownBy(() -> SubscriptionService.normalise("   "))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("empty_url"));
    }

    @Test
    @DisplayName("TC-API-01: a body without a url never reaches the resolver")
    void aBlankUrlIsRejectedByValidationNotByTheNetwork() throws Exception {
        mvc.perform(write(post("/api/feeds"), Map.of("url", "")))
            .andExpect(status().isBadRequest());
        mvc.perform(write(post("/api/feeds"), Map.of("group", "Tech")))
            .andExpect(status().isBadRequest());
        assertThat(db.sql("select count(*) from feed").query(Long.class).single()).isZero();

        // The non-http schemes are refused here, before the guard is consulted, so the message stays specific.
        mvc.perform(write(post("/api/feeds"), Map.of("url", "file:///etc/passwd")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:scheme_not_allowed"));
    }

    @Test
    @DisplayName("TC-SUB-31/36: the interval has bounds, an empty group means out of the group, 0 means follow the site")
    void intervalAndGroupEditsAreBoundedAndExplicit() throws Exception {
        long id = add(server.url("/feed"), "Tech");

        mvc.perform(write(patch("/api/feeds/" + id), Map.of("intervalMinutes", -1)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:bad_interval"));
        mvc.perform(write(patch("/api/feeds/" + id), Map.of("intervalMinutes", 43201)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:bad_interval"));
        mvc.perform(write(patch("/api/feeds/" + id), Map.of("intervalMinutes", 43200)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fetchIntervalMinutes").value(43200));
        mvc.perform(write(patch("/api/feeds/" + id), Map.of("intervalMinutes", 1)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fetchIntervalMinutes").value(1));

        // Absent means "unchanged", so the interval survives a group-only edit.
        mvc.perform(write(patch("/api/feeds/" + id), Map.of("group", "")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fetchIntervalMinutes").value(1));
        // 0 is the one spelling of "stop overriding", and it has to reach the column as NULL rather than as a zero-minute
        // cadence, which the fetcher would read as "no override" while the health page showed a number.
        mvc.perform(write(patch("/api/feeds/" + id), Map.of("intervalMinutes", 0)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fetchIntervalMinutes").doesNotExist());
        assertThat(db.sql("select fetch_interval_minutes from feed where id = ?")
            .param(id).query(Integer.class).optional()).isEmpty();
        // non_null inclusion: a row with no group has no groupName key at all, which is how the UI reads "no group".
        mvc.perform(get("/api/feeds/" + id).session(session))
            .andExpect(jsonPath("$.groupName").doesNotExist());
        // ...and the group picker must not grow a blank entry, which is what storing "" would have done.
        mvc.perform(get("/api/feeds/groups").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(write(patch("/api/feeds/" + id), Map.of("state", "muted")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("muted"));
    }

    @Test
    @DisplayName("TC-SUB-37: the operator's label is what every read path shows, and the address is not editable")
    void aFeedCanBeRenamedButNeverRepointed() throws Exception {
        long id = add(server.url("/feed"));

        mvc.perform(write(patch("/api/feeds/" + id), Map.of("title", "我的订阅名")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.customTitle").value("我的订阅名"));

        // The publisher's own name is a different column, so the two owners cannot overwrite each other; this is what
        // a fetched round leaves behind (EntryIngestServiceTest proves the real ingest path does the same).
        db.sql("update feed set title = ? where id = ?").params("Loopback Weekly", id).update();
        mvc.perform(get("/api/feeds/" + id).session(session))
            .andExpect(jsonPath("$.title").value("Loopback Weekly"))
            .andExpect(jsonPath("$.customTitle").value("我的订阅名"));

        // The inbox reads the same resolution, or one feed would be two different names on two pages.
        insertEntry(id, "loop-1");
        mvc.perform(get("/api/entries?limit=1").session(session))
            .andExpect(jsonPath("$.items[0].feedTitle").value("我的订阅名"));

        // An empty title means "put the site's name back", not a row whose label is blank.
        mvc.perform(write(patch("/api/feeds/" + id), Map.of("title", "")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.customTitle").doesNotExist());
        mvc.perform(get("/api/entries?limit=1").session(session))
            .andExpect(jsonPath("$.items[0].feedTitle").value("Loopback Weekly"));

        // Too long to store is refused rather than silently cut.
        mvc.perform(write(patch("/api/feeds/" + id), Map.of("title", "x".repeat(513))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:bad_title"));

        // The URL is not a settable field, and asking for it fails loudly: a patch that ignored it would answer 200
        // about an edit that never happened, and entries, queue row and dedupe key are all attached to that address.
        mvc.perform(write(patch("/api/feeds/" + id),
            Map.of("canonicalUrl", server.url("/other").toString())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:unknown_field"));
        mvc.perform(get("/api/feeds/" + id).session(session))
            .andExpect(jsonPath("$.canonicalUrl").value(server.url("/feed").toString()));
    }

    @Test
    @DisplayName("TC-SUB-38: the page asks this install what its default cadence is instead of guessing")
    void theFetchPolicyReflectsTheConfiguredInterval() throws Exception {
        mvc.perform(get("/api/feeds/policy").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.defaultIntervalMinutes").value(60))
            .andExpect(jsonPath("$.minIntervalMinutes").value(5));
    }

    @Test
    @DisplayName("TC-SUB-23: an import works whatever the operating system calls a .opml file")
    void opmlImportAcceptsEveryContentTypeABrowserMightSend() throws Exception {
        for (MediaType type : List.of(MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM,
            MediaType.TEXT_XML, MediaType.APPLICATION_XML)) {
            // A fresh address per round: the second outline for a URL the library already has would be skipped as a
            // duplicate, which would make this pass for the wrong reason.
            String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <opml version="2.0"><body>
                  <outline type="rss" text="Typed" xmlUrl="%1$s/plain"/>
                </body></opml>
                """.formatted(server.url("/via-" + subtypeOf(type)));
            Cookie csrf = csrf();
            long before = db.sql("select count(*) from feed").query(Long.class).single();
            // A refused media type would answer 415, which on Windows looks like "the importer is broken".
            mvc.perform(post("/api/opml").session(session).contentType(type).content(xml)
                    .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.skipped").value(0));
            assertThat(db.sql("select count(*) from feed").query(Long.class).single()).isEqualTo(before + 1);
        }
    }

    /** A filesystem-safe slug out of a media type, used to give every round its own address. */
    private static String subtypeOf(MediaType type) {
        return type.toString().replaceAll("[^a-zA-Z0-9]", "-");
    }

    @Test
    @DisplayName("TC-SUB-04: when a page offers three formats, the default is JSON")
    void aPageOfferingEveryFormatDefaultsToTheMostModernOne() throws Exception {
        server.on("/site", LocalFeedServer.Scenario.ok("""
            <!doctype html><html><head>
            <link rel="alternate" type="application/rss+xml" title="RSS" href="/site/rss.xml">
            <link rel="alternate" type="application/feed+json" title="JSON" href="/site/feed.json">
            <link rel="alternate" type="application/atom+xml" title="Atom" href="/site/atom.xml">
            </head><body>hi</body></html>
            """, HTML_HEADERS));

        JsonNode body = read(mvc.perform(write(post("/api/feeds/discover"), Map.of("url", server.url("/site"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.candidates.length()").value(3))
            .andReturn());

        assertThat(body.get("feedUrl").asString()).isEqualTo(server.url("/site/feed.json"));
        // Candidates stay in document order so the picker shows what the publisher listed, not a re-sorted guess.
        assertThat(body.get("candidates").valueStream().map(node -> node.get("url").asString()).toList())
            .containsExactly(server.url("/site/rss.xml"), server.url("/site/feed.json"),
                server.url("/site/atom.xml"));
    }

    @Test
    @DisplayName("TC-SUB-28: an import queues its own work in order instead of opening one socket per outline")
    void importedFeedsAreStaggeredAndNeverProbedDuringImport() throws Exception {
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <opml version="2.0"><body>
              <outline type="rss" text="One" xmlUrl="%1$s/i1"/>
              <outline type="rss" text="Two" xmlUrl="%1$s/i2"/>
              <outline type="rss" text="Three" xmlUrl="%1$s/i3"/>
            </body></opml>
            """.formatted(server.url(""));

        mvc.perform(write(post("/api/opml"), null, xml))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.imported").value(3));

        // The exporter already resolved these; re-discovering two hundred of them would make the import take minutes
        // and fail on whichever host happens to be down.
        assertThat(server.requests()).isEmpty();

        List<java.sql.Timestamp> fireAts = db.sql("select st.fire_at from scheduled_task st join feed f on f.id = st.ref_id"
                + " where st.kind = 'fetch' order by f.canonical_url")
            .query(java.sql.Timestamp.class).list();
        assertThat(fireAts).hasSize(3);
        assertThat(Duration.between(fireAts.get(0).toInstant(), fireAts.get(1).toInstant()))
            .isGreaterThan(Duration.ofSeconds(9));
        assertThat(Duration.between(fireAts.get(0).toInstant(), fireAts.get(2).toInstant()))
            .isGreaterThan(Duration.ofSeconds(19));
    }

    @Test
    @DisplayName("TC-API-06, TC-RDR-10: the group list is sorted, and applied counts rows matched")
    void groupsAreDeduplicatedAndARepeatStateWriteIsHarmless() throws Exception {
        long first = add(server.url("/feed"), "Tech");
        add(server.url("/other"), "News");
        add(server.url("/third"), "Tech");

        mvc.perform(get("/api/feeds/groups").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0]").value("News"))
            .andExpect(jsonPath("$[1]").value("Tech"));

        insertEntry(first, "once", "unread", false);
        long entryId = entryId(first, "once");
        Map<String, Object> read = Map.of("ids", List.of(entryId), "readState", "read");
        mvc.perform(write(post("/api/entries/state"), read))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.applied").value(1));
        // The second call changes nothing and still reports one row: the UI must not read this as "it worked twice".
        mvc.perform(write(post("/api/entries/state"), read))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.applied").value(1));
        assertThat(db.sql("select read_state from entry where id = ?").param(entryId).query(String.class).single())
            .isEqualTo("read");
    }

    @Test
    @DisplayName("TC-HEA-02/03/04: problem rows, failing rows and quiet rows are three different counts")
    void theHealthSummaryKeepsItsThreeDefinitionsApart() throws Exception {
        long twoDays = add(server.url("/feed"));
        db.sql("update feed set last_change_at = ? where id = ?")
            .params(java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(2))), twoDays).update();
        // An error message with no failure count: the row is worth tinting, but it is not a source that is failing.
        long messageOnly = add(server.url("/broken"));
        db.sql("update feed set last_error = 'parse_error' where id = ?").param(messageOnly).update();
        long fourDays = add(server.url("/silent"));
        db.sql("update feed set last_change_at = ? where id = ?")
            .params(java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(4))), fourDays).update();
        long gone = add(server.url("/gone"));
        db.sql("update feed set state = 'gone' where id = ?").param(gone).update();

        JsonNode body = read(mvc.perform(get("/api/health/feeds").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.summary.total").value(4))
            .andExpect(jsonPath("$.summary.failing").value(1))
            .andExpect(jsonPath("$.summary.quiet").value(1))
            .andReturn());

        // Nothing has ever been fetched, so there is no slowest round to report — and the key is absent, not null.
        assertThat(body.get("summary").has("slowestFetchMs")).as("non_null inclusion").isFalse();
        assertThat(body.get("feeds").valueStream().filter(row -> row.get("problem").asBoolean()).count())
            .as("two of the four rows are problems: the message and the gone source")
            .isEqualTo(2);
        // Problems first; two problem rows with no content history tie on quiet time and fall back to id order.
        assertThat(body.get("feeds").get(0).get("id").asLong()).isEqualTo(messageOnly);
        assertThat(body.get("feeds").get(1).get("id").asLong()).isEqualTo(gone);
        // Then the quietest source, longest silence first: four days outranks two.
        assertThat(body.get("feeds").get(2).get("id").asLong()).isEqualTo(fourDays);
        assertThat(body.get("feeds").get(3).get("id").asLong()).isEqualTo(twoDays);
    }

    private long add(String url) throws Exception {
        return add(url, null);
    }

    private long add(String url, String group) throws Exception {
        Map<String, Object> body = group == null ? Map.of("url", url) : Map.of("url", url, "group", group);
        return read(mvc.perform(write(post("/api/feeds"), body))
            .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    private String taskState(long feedId) {
        return db.sql("select state from scheduled_task where kind = 'fetch' and dedupe_key = ?")
            .param("feed:" + feedId)
            .query(String.class)
            .single();
    }

    private long entryId(long feedId, String guid) {
        return db.sql("select id from entry where feed_id = ? and guid = ?")
            .params(feedId, guid).query(Long.class).single();
    }

    private void insertEntry(long feedId, String guid) {
        insertEntry(feedId, guid, "unread", false, "2026-10-10T00:00:00Z");
    }

    private void insertEntry(long feedId, String guid, String readState, boolean baseline) {
        insertEntry(feedId, guid, readState, baseline, "2026-10-10T00:00:00Z");
    }

    private void insertEntry(long feedId, String guid, String readState, boolean baseline, String publishedAt) {
        insertEntry(feedId, guid, readState, baseline, publishedAt, "[]");
    }

    /**
     * The row as ingest writes it. {@code categoriesJson} is the column verbatim: the JSON array text
     * {@code EntryCodec} produces, quotes and no spaces — which is what makes a filter that matches that text
     * instead of a name a filter that cannot report a row the reader pane does not show a tag for.
     */
    private void insertEntry(long feedId, String guid, String readState, boolean baseline, String publishedAt,
        String categoriesJson) {
        db.sql("insert into entry (feed_id, guid, url, title, content_html, authors, categories, read_state, "
                + "baseline, published_at, fetched_at, meta) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
            .params(feedId, guid, server.url("/blog/" + guid), guid, "<p>body of " + guid + "</p>",
                "[{\"name\":\"Ann\"}]", categoriesJson, readState, baseline,
                java.sql.Timestamp.from(Instant.parse(publishedAt)), java.sql.Timestamp.from(Instant.parse(publishedAt)),
                "{\"guid_source\":\"guid\",\"stable_id\":true}")
            .update();
    }

    private void insertCategorised(long feedId, String guid, String categoriesJson) {
        insertEntry(feedId, guid, "unread", false, "2026-10-10T00:00:00Z", categoriesJson);
    }

    /**
     * A rule written directly, because only its name matters here — the marking itself is what {@code RulesApiTest}
     * and {@code RuleDeliveryTest} produce through the API.
     */
    private long rule(String name) {
        db.sql("insert into rule (name, match, actions) values (?, '{\"includeAny\":[\"oom\"]}', "
                + "'[{\"channel\":\"markOnly\"}]')").param(name).update();
        return db.sql("select id from rule where name = ?").param(name).query(Long.class).single();
    }

    /** The row the ingest side writes in the same transaction as the entry it judged (03 §8.1's "hit fact"). */
    private void markAlerted(long ruleId, long entryId) {
        db.sql("insert into notification_log (rule_id, entry_id, channel, dedupe_key, state) "
                + "values (?, ?, 'markOnly', ?, 'sent')")
            .params(ruleId, entryId, "rule:" + ruleId + ":perFeed:feed:" + entryId)
            .update();
    }

    /** A list request the way the SPA sends it, with the parameter kept out of the URL so a wildcard stays one. */
    private JsonNode page(MockHttpServletRequestBuilder request) throws Exception {
        return read(mvc.perform(request.session(session))
            .andExpect(status().isOk())
            .andReturn());
    }

    private List<String> pageOfTitles(MockHttpServletRequestBuilder request) throws Exception {
        return titles(page(request));
    }

    private List<String> categorised(String category) throws Exception {
        return pageOfTitles(get("/api/entries?state=all").param("category", category));
    }

    private List<String> categorisedQuery(String category, String query) throws Exception {
        return pageOfTitles(get("/api/entries?state=all").param("category", category).param("q", query));
    }

    private static List<String> titles(JsonNode page) {
        return page.get("items").valueStream().map(node -> node.get("title").asString()).toList();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** Everything that changes state goes through here: session cookie, CSRF cookie and CSRF header together. */
    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, Map<String, Object> body)
        throws Exception {
        return write(request, body, null);
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, Map<String, Object> body,
        String rawBody) throws Exception {
        MockHttpServletRequestBuilder builder = request.session(session);
        if (body != null) {
            builder = builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        } else if (rawBody != null) {
            builder = builder.contentType(MediaType.APPLICATION_XML).content(rawBody);
        }
        Cookie csrf = csrf();
        return builder.cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) throws Exception {
        Cookie csrf = csrf();
        return request.session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
    }

    /** Boots the token the way the SPA does on a cold start; the repository keeps no server-side copy. */
    private Cookie csrf() throws Exception {
        Cookie cookie = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).as("XSRF-TOKEN must be readable by the browser that echoes it").isNotNull();
        return cookie;
    }
}
