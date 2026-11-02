package com.rsshub.feedparse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The dirty corpus 02 §3 asks the parser to survive, one fixture per shape, plus the sniffer's rejection paths.
 * Everything goes through the public {@link FeedParser} entry point rather than the readers, because the order of
 * decode → sniff → dispatch is part of what is being tested.
 */
class FeedParserTest {

    private final FeedParser parser = new FeedParser();

    private static final String RSS_URL = "https://blog.example.com/feed.xml";
    private static final String ATOM_URL = "https://atom.example.com/feed";
    private static final String RDF_URL = "https://rdf.example.com/rss10.xml";
    private static final String JSON_URL = "https://json.example.com/feed.json";

    @Test
    void readsACleanRss2FeedWithNamespacesCdataAndEnclosures() {
        NormalizedFeed feed = parser.parse(fixture("rss2-blog.xml"), RSS_URL);
        assertThat(feed.format()).isEqualTo(FeedFormat.RSS2);
        assertThat(feed.title()).isEqualTo("Example Blog");
        assertThat(feed.siteUrl()).isEqualTo("https://blog.example.com/");
        assertThat(feed.feedUrl()).isEqualTo("https://blog.example.com/feed.xml");
        assertThat(feed.description()).isEqualTo("Notes about things, & other stuff");
        assertThat(feed.language()).isEqualTo("en-us");
        assertThat(feed.ttlMinutes()).isEqualTo(30);
        assertThat(feed.entries()).hasSize(2);

        NormalizedEntry first = feed.entries().get(0);
        assertThat(first.guid()).isEqualTo("https://blog.example.com/2026/post-one");
        assertThat(first.guidSource()).isEqualTo(GuidSource.GUID);
        assertThat(first.idIsStable()).isTrue();
        assertThat(first.title()).isEqualTo("Post <b>one</b> & friends");
        // 09:00 +0800 is 01:00Z
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-10-18T01:00:00Z"));
        assertThat(first.authors()).containsExactly(new NormalizedEntry.Author("Wei Zhang", null, null));
        assertThat(first.categories()).containsExactly("tech", "java", "jvm");

        // content:encoded won over description (03 §5.5), the script and the pixel are gone, and every relative
        // address was resolved against the entry's own link.
        assertThat(first.contentHtml()).contains("<p>Body with <a href=\"https://blog.example.com/2026/post-one#s\""
                + " target=\"_blank\" rel=\"noopener noreferrer\">a fragment link</a>.</p>")
            .contains("https://blog.example.com/img/one.png")
            .doesNotContain("script", "mailchimp");
        assertThat(first.summary()).contains("https://blog.example.com/2026/post-one").doesNotContain("<a href=\"/");
        assertThat(first.enclosures()).containsExactly(
            new NormalizedEntry.Enclosure("https://blog.example.com/media/one.mp3", "audio/mpeg", 1024L, null));

        NormalizedEntry second = feed.entries().get(1);
        assertThat(second.guid()).isEqualTo("tag:example.com,2026:/post-two");
        assertThat(second.guidSource()).isEqualTo(GuidSource.GUID);
        assertThat(second.url()).isEqualTo("https://blog.example.com/2026/post-two");
        // RSS 2.0's author is an RFC 822 mailbox: the parenthesised part is the name, the angle-less part is the
        // address, and a reader that shows only one of them shows the wrong one.
        assertThat(second.authors())
            .containsExactly(new NormalizedEntry.Author("A Bot", "robot@example.com", null));
        assertThat(second.summary()).contains("<em>emphasised</em>");
        assertThat(second.enclosures()).containsExactly(
            new NormalizedEntry.Enclosure("https://cdn.example.com/v.mp4", "video/mp4", 4096L, 120L));
    }

    @Test
    void survivesUndeclaredPrefixesRelativeLinksAndItemsWithNothingInThem() {
        NormalizedFeed feed = parser.parse(fixture("rss2-messy.xml"), "https://blog.example.com/feed/");
        assertThat(feed.format()).isEqualTo(FeedFormat.RSS2);
        assertThat(feed.language()).isEqualTo("zh-cn");
        assertThat(feed.siteUrl()).isEqualTo("https://blog.example.com/blog");
        assertThat(feed.entries()).hasSize(4);

        NormalizedEntry relative = feed.entries().get(0);
        assertThat(relative.url()).isEqualTo("https://blog.example.com/blog/2026/relative");
        // An unreadable pubDate is a missing date, not a failed fetch.
        assertThat(relative.publishedAt()).isNull();
        assertThat(relative.guidSource()).isEqualTo(GuidSource.LINK);
        assertThat(relative.authors()).containsExactly(new NormalizedEntry.Author("The Robot", "robot@example.com",
            null));
        assertThat(relative.summary()).contains("bare &amp; ampersand");

        NormalizedEntry dcOnly = feed.entries().get(1);
        assertThat(dcOnly.title()).isEqualTo("Title only in dc:title");
        assertThat(dcOnly.publishedAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(dcOnly.guidSource()).isEqualTo(GuidSource.FALLBACK_HASH);
        assertThat(dcOnly.idIsStable()).isFalse();
        assertThat(dcOnly.url()).isNull();
        assertThat(dcOnly.contentHtml()).contains("<p>markup</p>");
        assertThat(dcOnly.extras()).containsEntry("itunes:duration", "1:02:03");

        NormalizedEntry empty = feed.entries().get(2);
        assertThat(empty.title()).isNull();
        assertThat(empty.guid()).startsWith(GuidSource.FALLBACK_HASH.stored() + ":");
        assertThat(empty.contentHtml()).isNull();

        NormalizedEntry perma = feed.entries().get(3);
        assertThat(perma.url()).isEqualTo("https://blog.example.com/2026/perma");
        assertThat(perma.publishedAt()).isEqualTo(Instant.parse("2026-10-18T09:30:00Z"));
        assertThat(perma.guidSource()).isEqualTo(GuidSource.GUID);
    }

    @Test
    void readsAtomLinksByIdAttributeAndKeepsSelfOutOfTheAlternateSlot() {
        NormalizedFeed feed = parser.parse(fixture("atom-blog.xml"), ATOM_URL);
        assertThat(feed.format()).isEqualTo(FeedFormat.ATOM);
        assertThat(feed.title()).isEqualTo("Atom Example & Co");
        assertThat(feed.siteUrl()).isEqualTo("https://atom.example.com/");
        assertThat(feed.feedUrl()).isEqualTo("https://atom.example.com/feed");
        assertThat(feed.language()).isEqualTo("en");
        assertThat(feed.entries()).hasSize(3);

        NormalizedEntry first = feed.entries().get(0);
        // rel=edit and the nonsense rel must never win over rel=alternate, and /posts/1?q=1 is relative to the feed
        assertThat(first.url()).isEqualTo("https://atom.example.com/posts/1?q=1");
        assertThat(first.guid()).isEqualTo("tag:atom.example.com,2026:/posts/1");
        assertThat(first.guidSource()).isEqualTo(GuidSource.ATOM_ID);
        assertThat(first.title()).isEqualTo("Rich title");
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-10-18T02:00:00Z"));
        assertThat(first.updatedAt()).isEqualTo(Instant.parse("2026-10-18T01:00:00.500Z"));
        assertThat(first.authors()).containsExactly(
            new NormalizedEntry.Author("Ann", "ann@example.com", "https://ann.example.com"),
            new NormalizedEntry.Author("Volunteer", null, null));
        assertThat(first.categories()).containsExactly("tech", "atom");
        assertThat(first.enclosures()).containsExactly(
            new NormalizedEntry.Enclosure("https://cdn.example.com/1.mp3", "audio/mpeg", 2048L, null));
        // type=xhtml content: the wrapper div is not part of the body, and the relative links resolve against the
        // entry URL, not the feed URL.
        assertThat(first.contentHtml()).contains("<p>Body <a href=\"https://atom.example.com/other\"")
            .contains("https://atom.example.com/posts/i.png")
            .doesNotStartWith("<div")
            .contains("<iframe src=\"https://www.youtube.com/embed/abc\"");
        assertThat(first.summary()).contains("a &lt; and an &amp;");
        assertThat(first.extras()).containsEntry("thr:total", "4");

        NormalizedEntry textOnly = feed.entries().get(1);
        assertThat(textOnly.contentHtml()).isEqualTo("a &lt; b &amp; c &gt; d");
        assertThat(textOnly.publishedAt()).isEqualTo(Instant.parse("2026-10-18T06:00:00Z"));
        assertThat(textOnly.updatedAt()).isEqualTo(Instant.parse("2026-10-18T06:00:00Z"));

        NormalizedEntry noLink = feed.entries().get(2);
        assertThat(noLink.url()).isNull();
        assertThat(noLink.guidSource()).isEqualTo(GuidSource.ATOM_ID);
        assertThat(noLink.contentHtml()).isNull();
    }

    @Test
    void readsRss10RdfWhereItemsAreSiblingsOfTheChannel() {
        NormalizedFeed feed = parser.parse(fixture("rdf-blog.xml"), RDF_URL);
        assertThat(feed.format()).isEqualTo(FeedFormat.RDF);
        assertThat(feed.title()).isEqualTo("RSS 1.0 Reader");
        assertThat(feed.language()).isEqualTo("ja");
        // sy:updatePeriod=hourly, frequency=3 → 180 minutes; there is no <ttl> anywhere in the channel.
        assertThat(feed.ttlMinutes()).isEqualTo(180);
        assertThat(feed.entries()).hasSize(2);

        NormalizedEntry first = feed.entries().get(0);
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-10-18T01:02:03Z"));
        assertThat(first.guidSource()).isEqualTo(GuidSource.LINK);
        assertThat(first.categories()).containsExactly("alpha", "beta");
        assertThat(first.authors()).containsExactly(new NormalizedEntry.Author("Hana", null, null));

        NormalizedEntry second = feed.entries().get(1);
        // "JST" is one of the three-letter abbreviations the JDK's legacy zone table resolves (+09:00), so the date
        // survives; an abbreviation it does not know is dropped by the same ladder without losing the entry.
        assertThat(second.publishedAt()).isEqualTo(Instant.parse("2026-10-17T20:00:00Z"));
        assertThat(second.contentHtml()).isEqualTo("<p>Undeclared prefix body</p>");
    }

    @Test
    void readsSyndicationTtlItunesDurationAndKeepsUnknownNamespacesInExtras() {
        NormalizedFeed feed = parser.parse(fixture("rss-sy-ttl.xml"), "https://sy.example.com/feed.xml");
        assertThat(feed.ttlMinutes()).isEqualTo(2880);
        NormalizedEntry episode = feed.entries().get(0);
        assertThat(episode.enclosures()).containsExactly(
            new NormalizedEntry.Enclosure("https://cdn.example.com/e1.mp3", "audio/mpeg", 8000L, 3600L));
        // Fields the core model does not carry are text, and nothing else may read them (03 §5.2).
        assertThat(episode.extras()).containsEntry("wfw:commentrss", "https://sy.example.com/e1/comments");
        assertThat(episode.extras()).containsEntry("itunes:explicit", "no");
        // itunes:author and content:encoded are mapped or deliberately dropped, so they must not be duplicated here.
        assertThat(episode.extras()).doesNotContainKey("itunes:author");
        assertThat(feed.entries().get(1).guid()).isEqualTo("e2");
    }

    @Test
    void readsJsonFeed11AndItsExtensions() {
        NormalizedFeed feed = parser.parse(fixture("json-feed-11.json"), JSON_URL);
        assertThat(feed.format()).isEqualTo(FeedFormat.JSON);
        assertThat(feed.title()).isEqualTo("JSON Feed 1.1");
        assertThat(feed.siteUrl()).isEqualTo("https://json.example.com/");
        assertThat(feed.feedUrl()).isEqualTo("https://json.example.com/feed.json");
        assertThat(feed.language()).isEqualTo("en");
        assertThat(feed.entries()).hasSize(4);

        NormalizedEntry first = feed.entries().get(0);
        assertThat(first.guid()).isEqualTo("urn:uuid:9d5a");
        assertThat(first.guidSource()).isEqualTo(GuidSource.JSON_ID);
        // "url" wins over "external_url": external_url is for a feed that links out to somebody else's post.
        assertThat(first.url()).isEqualTo("https://json.example.com/posts/one");
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-10-18T01:00:00Z"));
        assertThat(first.updatedAt()).isEqualTo(Instant.parse("2026-10-17T18:00:00Z"));
        assertThat(first.lang()).isEqualTo("en-gb");
        assertThat(first.categories()).containsExactly("tech", "json");
        assertThat(first.authors())
            .containsExactly(new NormalizedEntry.Author("Ann", null, "https://ann.example.com"));
        assertThat(first.enclosures()).containsExactly(
            new NormalizedEntry.Enclosure("https://json.example.com/media/one.mp3", "audio/mpeg", 4096L, 125L),
            new NormalizedEntry.Enclosure("https://cdn.example.com/two.mp3", "audio/mpeg", null, null));
        // A protocol-relative image is resolved against the entry URL's scheme, exactly as a browser would.
        assertThat(first.contentHtml()).contains("<img src=\"https://cdn.example.com/x.png\">")
            .contains("https://json.example.com/posts/one");
        assertThat(first.extras()).containsEntry("custom_extension", "kept in extras");
        assertThat(first.extras()).doesNotContainKey("custom_object");

        NormalizedEntry numeric = feed.entries().get(1);
        // 03 §5.3 rule 3: a numeric id is a string, not a decimal.
        assertThat(numeric.guid()).isEqualTo("12345");
        assertThat(numeric.url()).isNull();
        assertThat(numeric.contentHtml()).isEqualTo("a &lt; b &amp; c");

        NormalizedEntry noId = feed.entries().get(2);
        assertThat(noId.guid()).isEqualTo("https://json.example.com/posts/no-id");
        assertThat(noId.guidSource()).isEqualTo(GuidSource.LINK);
        assertThat(noId.publishedAt()).isNull();

        NormalizedEntry junk = feed.entries().get(3);
        assertThat(junk.contentHtml()).isNull();
        assertThat(junk.publishedAt()).isEqualTo(Instant.ofEpochSecond(1_760_780_000L));
        assertThat(junk.categories()).containsExactly("not an array");
    }

    @Test
    void readsJsonFeed10AuthorShapesAndPlainContent() {
        NormalizedFeed feed = parser.parse(fixture("json-feed-10.json"), "https://old.example.com/feed.json");
        assertThat(feed.siteUrl()).isNull();
        // No feed_url in the document: the address we subscribed to is the only honest answer.
        assertThat(feed.feedUrl()).isEqualTo("https://old.example.com/feed.json");
        assertThat(feed.entries().get(0).authors())
            .containsExactly(new NormalizedEntry.Author("Old Timer", null, null));
        assertThat(feed.entries().get(0).contentHtml()).isEqualTo("<p>Both content keys existed in 1.0</p>");
        assertThat(feed.entries().get(1).authors())
            .containsExactly(new NormalizedEntry.Author("Ann", null, "https://ann.example.com"));
        assertThat(feed.entries().get(1).enclosures()).hasSize(1);
    }

    @Test
    void sniffsByTheFirstRealTagAndNotByTheContentType() {
        String atomBody = fixture("atom-blog.xml");
        // A mislabelled Content-Type must not change the reader that runs: 03 §5.2 sniffs the document.
        assertThat(parser.parse(atomBody.getBytes(StandardCharsets.UTF_8), "application/octet-stream", ATOM_URL)
            .format()).isEqualTo(FeedFormat.ATOM);
        assertThat(parser.parse(fixture("rss2-blog.xml").getBytes(StandardCharsets.UTF_8), "text/html", RSS_URL)
            .format()).isEqualTo(FeedFormat.RSS2);
    }

    @Test
    void takesTheCharsetFromTheContentTypeWhenDecodingBytes() {
        byte[] gbk = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<rss version=\"2.0\"><channel><title>中文频道</title><item><title>中文条目</title>"
            + "<link>https://cn.example.com/1</link></item></channel></rss>")
            .replace("encoding=\"UTF-8\"", "encoding=\"GB2312\"")
            .getBytes(Charset.forName("GB18030"));
        NormalizedFeed feed = parser.parse(gbk, "application/rss+xml; charset=gbk", "https://cn.example.com/feed");
        assertThat(feed.title()).isEqualTo("中文频道");
        assertThat(feed.entries().get(0).title()).isEqualTo("中文条目");
    }

    @Test
    void rejectsThingsThatAreNotFeedsWithAReasonTheOperatorCanActOn() {
        assertThatThrownBy(() -> parser.parse("<html><body><p>hi</p></body></html>", RSS_URL))
            .isInstanceOf(FeedParseException.class)
            .hasMessageContaining("HTML page");
        assertThatThrownBy(() -> parser.parse("<opml version=\"2.0\"><body/></opml>", RSS_URL))
            .isInstanceOf(FeedParseException.class)
            .hasMessageContaining("OPML");
        assertThatThrownBy(() -> parser.parse("just some words", RSS_URL))
            .isInstanceOf(FeedParseException.class)
            .hasMessageContaining("neither");
        assertThatThrownBy(() -> parser.parse("", RSS_URL)).isInstanceOf(FeedParseException.class)
            .hasMessageContaining("empty");
        assertThatThrownBy(() -> parser.parse(null, RSS_URL)).isInstanceOf(FeedParseException.class);
        assertThatThrownBy(() -> parser.parse("{\"hello\":1}", JSON_URL))
            .isInstanceOf(FeedParseException.class)
            .hasMessageContaining("jsonfeed.org");
        assertThatThrownBy(() -> parser.parse("{not json", JSON_URL))
            .isInstanceOf(FeedParseException.class)
            .hasMessageContaining("malformed JSON");
        assertThatThrownBy(() -> parser.parse("<rss version=\"2.0\"><spam/></rss>", RSS_URL))
            .isInstanceOf(FeedParseException.class)
            .hasMessageContaining("<channel>");
        assertThatThrownBy(() -> parser.parse("<html", RSS_URL)).isInstanceOf(FeedParseException.class);
    }

    @Test
    void parsingTheSameBytesTwiceGivesTheSameModel() {
        // feedstore compares content hashes across fetches; a parser that is not deterministic turns quiet feeds
        // into permanent churn.
        String rss = fixture("rss2-blog.xml");
        assertThat(parser.parse(rss, RSS_URL)).isEqualTo(parser.parse(rss, RSS_URL));
        String json = fixture("json-feed-11.json");
        assertThat(parser.parse(json, JSON_URL)).isEqualTo(parser.parse(json, JSON_URL));
    }

    private static String fixture(String name) {
        try (InputStream in = FeedParserTest.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
