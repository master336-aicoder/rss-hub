package com.rsshub.feedstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rsshub.feedfetch.FeedDocumentProcessor.Request;
import com.rsshub.feedfetch.FeedDocumentProcessor.Result;
import com.rsshub.feedparse.FeedParseException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 03 §5.4, and the two M1 gate lines that depend on it: "同一源连抓 3 次条目数不变" and "改 pubDate 不改 GUID →
 * 仍是同条目 + 新 revision". They are only provable against a real database, because every rule in that section is
 * about what the unique index and the SQL coalesces actually do.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:feedstore;DB_CLOSE_DELAY=-1",
    "rsshub.operator.username=admin",
    "rsshub.operator.password=a-test-only-password",
    "rsshub.fetch.enabled=false"
})
class EntryIngestServiceTest {

    private static final String FEED_URL = "https://ingest.example.com/feed.xml";

    @Autowired
    EntryIngestService ingest;
    @Autowired
    JdbcTemplate jdbc;

    private long feedId;

    @BeforeEach
    void createFeed() {
        jdbc.update("delete from entry");
        jdbc.update("delete from feed");
        jdbc.update("insert into feed (canonical_url, requested_url) values (?, ?)", FEED_URL, FEED_URL);
        feedId = jdbc.queryForObject("select id from feed", Long.class);
    }

    @Test
    void firstFetchStoresEveryEntryAsBaselineAndRecordsNoRevision() {
        Result result = ingest.process(request(document("One", "First body.", "2026-10-18T00:00:00Z")));

        assertThat(result.entryCount()).isEqualTo(2);
        assertThat(result.format()).isEqualTo("rss2");
        assertThat(result.changed()).isTrue();
        assertThat(jdbc.queryForList("select guid from entry where feed_id = ? order by guid", String.class, feedId))
            .containsExactly("g1", "g2");
        assertThat(column("baseline", "g1")).isEqualTo(true);
        assertThat(column("revision", "g1")).isEqualTo(1);
        assertThat(column("read_state", "g1")).isEqualTo("unread");
        // Revision rows are for changes; revision 1 is the entry row itself (03 §5.4).
        assertThat(jdbc.queryForObject("select count(*) from entry_revision", Integer.class)).isZero();
    }

    @Test
    void anIdenticalDocumentStoredTwiceChangesNothingAtAll() {
        String xml = document("One", "Same body every time.", "2026-10-18T00:00:00Z");
        ingest.process(request(xml));
        jdbc.update("update entry set read_state = 'read' where guid = 'g1'");
        Instant firstFetchedAt = instant("fetched_at", "g1");

        Result second = ingest.process(requestAt(xml, Instant.parse("2026-10-18T02:00:00Z")));

        assertThat(second.changed()).isFalse();
        assertThat(second.entryCount()).isEqualTo(2);
        assertThat(column("read_state", "g1")).isEqualTo("read");
        assertThat(column("revision", "g1")).isEqualTo(1);
        assertThat(instant("fetched_at", "g1")).isEqualTo(firstFetchedAt);
        assertThat(jdbc.queryForObject("select count(*) from entry_revision", Integer.class)).isZero();
    }

    @Test
    void aChangedBodyBecomesANewRevisionAndKeepsTheReadState() {
        ingest.process(request(document("One", "Original body.", "2026-10-18T00:00:00Z")));
        jdbc.update("update entry set read_state = 'read' where guid = 'g1'");

        Result result = ingest.process(requestAt(document("One", "Corrected body.", "2026-10-18T03:00:00Z"),
            Instant.parse("2026-10-18T04:00:00Z")));

        assertThat(result.changed()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from entry", Integer.class)).isEqualTo(2);
        assertThat(column("revision", "g1")).isEqualTo(2);
        assertThat(column("content_html", "g1")).isEqualTo("<p>Corrected body.</p>");
        assertThat(column("read_state", "g1")).isEqualTo("read");
        assertThat(instant("updated_at_src", "g1")).isEqualTo(Instant.parse("2026-10-18T03:00:00Z"));
        assertThat(jdbc.queryForList("select revision from entry_revision where entry_id = "
            + "(select id from entry where guid = 'g1') order by revision", Integer.class)).containsExactly(2);
    }

    @Test
    void anOlderSourceDateLosesToWhatIsAlreadyStored() {
        ingest.process(request(document("One", "Newer first.", "2026-10-18T09:00:00Z")));

        Result rewind = ingest.process(requestAt(document("One", "Rewound body.", "2026-10-18T01:00:00Z"),
            Instant.parse("2026-10-18T10:00:00Z")));

        assertThat(rewind.changed()).isFalse();
        assertThat(column("revision", "g1")).isEqualTo(1);
        assertThat(column("content_html", "g1")).isEqualTo("<p>Newer first.</p>");
        assertThat(jdbc.queryForObject("select count(*) from entry_revision", Integer.class)).isZero();
    }

    @Test
    void aDatelessSourceStillGetsItsCorrectionsRecorded() {
        String undated = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>No dates</title>
            <item><title>Only one</title><link>https://ingest.example.com/1</link>
            <guid>g1</guid><description>First body.</description></item>
            </channel></rss>""";
        ingest.process(request(undated));
        assertThat(column("revision", "g1")).isEqualTo(1);

        ingest.process(request(undated.replace("First body", "Second body")));

        assertThat(column("revision", "g1")).isEqualTo(2);
        assertThat(column("updated_at_src", "g1")).isNull();
    }

    @Test
    void aTitleEditIsARevisionOnTheSameRow() {
        ingest.process(request(document("Original title", "Body stays.", "2026-10-18T00:00:00Z")));

        ingest.process(requestAt(document("Retitled", "Body stays.", "2026-10-18T05:00:00Z"),
            Instant.parse("2026-10-18T06:00:00Z")));

        assertThat(jdbc.queryForObject("select count(*) from entry where guid = 'g1'", Integer.class)).isEqualTo(1);
        assertThat(column("title", "g1")).isEqualTo("Retitled");
        assertThat(column("revision", "g1")).isEqualTo(2);
    }

    @Test
    void theFallbackIdIsMarkedUnstableInMetaSoTheHealthPageCanWarn() {
        String noIds = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>Anonymous</title>
            <item><title>No guid, no link</title><description>Body.</description></item>
            </channel></rss>""";
        ingest.process(request(noIds));

        String guid = jdbc.queryForObject("select guid from entry", String.class);
        assertThat(guid).startsWith("fallback_hash:").hasSize("fallback_hash:".length() + 64);
        assertThat(jdbc.queryForObject("select meta from entry", String.class))
            .contains("\"guid_source\":\"fallback_hash\"")
            .contains("\"stable_id\":false");
    }

    @Test
    void peopleCategoriesAndEnclosuresAreStoredAsJsonText() {
        ingest.process(request(document("One", "Body.", "2026-10-18T00:00:00Z")));

        assertThat(jdbc.queryForObject("select authors from entry where guid = 'g1'", String.class))
            .isEqualTo("[{\"name\":\"Ann\",\"email\":\"ann@example.com\"}]");
        assertThat(jdbc.queryForObject("select categories from entry where guid = 'g1'", String.class))
            .isEqualTo("[\"tech\"]");
        assertThat(jdbc.queryForObject("select enclosures from entry where guid = 'g1'", String.class))
            .isEqualTo("[{\"url\":\"https://cdn.example.com/1.mp3\",\"mime_type\":\"audio/mpeg\","
                + "\"size_in_bytes\":1024}]");
    }

    @Test
    void theFeedReportsItsOwnMetadataThroughTheSameDocument() {
        ingest.process(request(document("One", "Body.", "2026-10-18T00:00:00Z")));

        Map<String, Object> row = jdbc.queryForMap("select title, site_url, description, language, format, "
            + "ttl_minutes, self_url from feed where id = ?", feedId);
        assertThat(row).containsEntry("title", "Ingest Feed")
            .containsEntry("site_url", "https://ingest.example.com/")
            .containsEntry("format", "rss2")
            .containsEntry("ttl_minutes", 30)
            .containsEntry("language", "en")
            .containsEntry("self_url", FEED_URL);
        assertThat(row.get("description")).isEqualTo("Feeds the ingest tests");
    }

    /**
     * The other half of the same document, and the reason {@code custom_title} and {@code fetch_interval_minutes}
     * exist as separate columns: this ingest writes the publisher's title and its {@code <ttl>}, and it must not
     * take the operator's choices down with it. Before the split, the operator's 15-minute cadence lived in
     * {@code ttl_minutes} and the site's 30 won on the first round after it was set.
     */
    @Test
    void theOperatorsOwnColumnsSurviveADocumentThatReportsBothTitleAndTtl() {
        jdbc.update("update feed set custom_title = ?, fetch_interval_minutes = ? where id = ?",
            "我的源", 15, feedId);

        ingest.process(request(document("One", "Body.", "2026-10-18T00:00:00Z")));

        Map<String, Object> row = jdbc.queryForMap("select title, custom_title, ttl_minutes, "
            + "fetch_interval_minutes from feed where id = ?", feedId);
        assertThat(row).containsEntry("title", "Ingest Feed")
            .containsEntry("ttl_minutes", 30)
            .containsEntry("custom_title", "我的源")
            .containsEntry("fetch_interval_minutes", 15);
    }

    @Test
    void aDocumentThatIsNotAFeedLeavesNoHalfStoredRows() {
        ingest.process(request(document("One", "Body.", "2026-10-18T00:00:00Z")));
        List<String> before = jdbc.queryForList("select guid from entry order by guid", String.class);

        assertThatThrownBy(() -> ingest.process(request("<html><body>hello</body></html>")))
            .isInstanceOf(FeedParseException.class);

        assertThat(jdbc.queryForList("select guid from entry order by guid", String.class)).isEqualTo(before);
    }

    private Request request(String xml) {
        return requestAt(xml, Instant.parse("2026-10-18T01:00:00Z"));
    }

    private Request requestAt(String xml, Instant at) {
        return new Request(feedId, FEED_URL, "application/rss+xml", xml.getBytes(StandardCharsets.UTF_8), at);
    }

    /**
     * Two items; the second one never changes in any test, which is what makes "条目数不变" visible rather than
     * asserted away. The publisher's update time is the Atom element, because RSS 2.0 has no per-item updated.
     */
    private static String document(String firstTitle, String firstBody, String firstUpdated) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom">
              <channel>
                <atom:link rel="self" href="https://ingest.example.com/feed.xml"/>
                <title>Ingest Feed</title>
                <link>https://ingest.example.com/</link>
                <description>Feeds the ingest tests</description>
                <language>en</language>
                <ttl>30</ttl>
                <item>
                  <guid>g1</guid>
                  <title>%s</title>
                  <link>https://ingest.example.com/1</link>
                  <pubDate>Sun, 18 Oct 2026 00:00:00 GMT</pubDate>
                  <atom:updated>%s</atom:updated>
                  <author>ann@example.com (Ann)</author>
                  <category>tech</category>
                  <enclosure url="https://cdn.example.com/1.mp3" type="audio/mpeg" length="1024"/>
                  <description><![CDATA[<p>%s</p>]]></description>
                </item>
                <item>
                  <guid>g2</guid>
                  <title>Two</title>
                  <link>https://ingest.example.com/2</link>
                  <pubDate>Sun, 18 Oct 2026 00:01:00 GMT</pubDate>
                  <description><![CDATA[<p>Second body.</p>]]></description>
                </item>
              </channel>
            </rss>""".formatted(firstTitle, firstUpdated, firstBody);
    }

    private Object column(String column, String guid) {
        return jdbc.queryForObject("select " + column + " from entry where feed_id = ? and guid = ?",
            Object.class, feedId, guid);
    }

    private Instant instant(String column, String guid) {
        OffsetDateTime value = jdbc.queryForObject("select " + column + " from entry where feed_id = ? and guid = ?",
            OffsetDateTime.class, feedId, guid);
        return value == null ? null : value.toInstant();
    }
}
