package com.rsshub.feedrender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rsshub.common.ApiException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import tools.jackson.databind.ObjectMapper;

/**
 * The seven writer rules from 03 §6.3, one assertion each where the rule is testable without a database. They exist
 * because every one of them is a "looks fine in the browser, rejected by the aggregator" failure, and the only cheap
 * way to catch those is to assert the bytes.
 */
class FeedWritersTest {

    private static final Instant PUBLISHED = Instant.parse("2026-10-17T01:02:03Z");
    private static final Instant UPDATED = Instant.parse("2026-10-18T04:05:06Z");

    private final Rss2Writer rss = new Rss2Writer();
    private final AtomWriter atom = new AtomWriter();
    private final JsonFeedWriter json = new JsonFeedWriter(new ObjectMapper());

    private static FeedMeta meta() {
        return new FeedMeta("测试站 Notes", "https://example.org/", "关于Observability的中文博客", "zh-CN", 60,
            "https://example.org/logo.png", "https://example.org/feed.rss", PUBLISHED, "rss-hub");
    }

    private static FeedItem item(String title, String url, String body) {
        return new FeedItem(url, url, title, "<p>摘要 with &amp; entity</p>", body, null, PUBLISHED, UPDATED,
            List.of("林一"), List.of("observability", "a<b"), List.of(), null, null, null);
    }

    private static final String TRICKY = "<p>a &lt;b&gt; tag, an &amp; ampersand, and ]]> in code</p>";

    @Nested
    @DisplayName("rule 1 and 3: the document starts at byte zero and declares every prefix it uses")
    class DocumentShape {

        @Test
        void xmlDeclarationIsTheFirstThingInTheFile() {
            for (FeedWriter writer : List.of(rss, atom)) {
                byte[] out = writer.render(meta(), List.of(item("Hello", "https://example.org/blog/hello", TRICKY)));
                assertThat(new String(out, StandardCharsets.UTF_8))
                    .as("%s must start with the declaration, not a BOM or a blank line", writer.format())
                    .startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
                assertThat(out[0]).as("a BOM is invisible in a diff and fatal to a strict parser")
                    .isNotEqualTo((byte) 0xEF);
            }
        }

        @Test
        void everyNamespacePrefixIsDeclaredOnTheRoot() {
            String out = new String(rss.render(meta(), List.of()), StandardCharsets.UTF_8);
            // The URIs are copied from doc/rss-quickref.md:143 verbatim, including content's and dc's exact spellings.
            assertThat(out).contains("xmlns:atom=\"http://www.w3.org/2005/Atom\"")
                .contains("xmlns:dc=\"http://purl.org/dc/elements/1.1/\"")
                .contains("xmlns:content=\"http://purl.org/rss/1.0/modules/content/\"");
            assertThat(out).contains("<rss version=\"2.0\"");
        }

        @Test
        void atomDeclaresItsDefaultNamespaceAndLanguage() {
            String out = new String(atom.render(meta(), List.of()), StandardCharsets.UTF_8);
            assertThat(out).contains("<feed xmlns=\"http://www.w3.org/2005/Atom\" xml:lang=\"zh-CN\">");
        }

        @Test
        void bothXmlOutlinesParseUnderAStrictParser() {
            // The gate says "严格 XML 解析通过"; a lenient regex check would not catch an undeclared prefix.
            assertThatCode(() -> parse(rss.render(meta(), List.of(item("T", "https://example.org/blog/t", TRICKY)))))
                .doesNotThrowAnyException();
            assertThatCode(() -> parse(atom.render(meta(), List.of(item("T", "https://example.org/blog/t", TRICKY)))))
                .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("rule 2: text is escaped, and a URL with a query string is the case that bites")
    class Escaping {

        @Test
        void angleBracketsAndAmpersandsBecomeEntitiesInText() {
            String out = new String(rss.render(meta(),
                List.of(item("5 < 7 & 8 > 6", "https://example.org/blog/x", "body"))), StandardCharsets.UTF_8);
            assertThat(out).contains("5 &lt; 7 &amp; 8 &gt; 6");
            assertThat(out).doesNotContain("5 < 7");
        }

        @Test
        void aQueryAmpersandIsEscapedInsideAnAttribute() {
            FeedItem withQuery = new FeedItem("https://example.org/blog/x?a=1&b=2",
                "https://example.org/blog/x?a=1&b=2", "Query", "s", null, null, PUBLISHED, UPDATED,
                List.of(), List.of(), List.of(), null, null, null);
            String out = new String(rss.render(meta(), List.of(withQuery)), StandardCharsets.UTF_8);
            assertThat(out).contains("guid isPermaLink=\"true\">https://example.org/blog/x?a=1&amp;b=2</guid>");
            assertThat(out).doesNotContain("?a=1&b=2");
        }

        @Test
        void escapingKeepsTheTextRecoverable() {
            byte[] out = rss.render(meta(), List.of(item("A & B", "https://example.org/blog/ab", "<p>plain</p>")));
            // The channel's own <title> is the first one in the document, so this has to be scoped to the item.
            Element title = tagIn(firstTag(parse(out), "item"), "title");
            assertThat(title.getTextContent()).isEqualTo("A & B");
        }
    }

    @Nested
    @DisplayName("rule 4: HTML bodies go inside CDATA and ]]> cannot close it early")
    class Cdata {

        @Test
        void contentEncodedWrapsTheBody() {
            String out = new String(rss.render(meta(), List.of(item("T", "https://example.org/blog/t",
                "<p>body</p>"))), StandardCharsets.UTF_8);
            assertThat(out).contains("<content:encoded><![CDATA[<p>body</p>]]></content:encoded>");
        }

        @Test
        void anEmbeddedCloseSequenceSurvivesRoundTrip() {
            FeedItem tricky = item("T", "https://example.org/blog/t", "<pre>]]>&amp;&lt;</pre>");
            byte[] out = rss.render(meta(), List.of(tricky));
            // If the split were wrong, the parse below would either fail or read a truncated body.
            Element encoded = firstTag(parse(out), "content:encoded");
            assertThat(encoded.getTextContent()).isEqualTo("<pre>]]>&amp;&lt;</pre>");
            assertThat(new String(out, StandardCharsets.UTF_8)).doesNotContain("]]>]]>");
        }

        @Test
        void bodyEntitiesAreNotDoubleEncoded() {
            String out = new String(rss.render(meta(), List.of(item("T", "https://example.org/blog/t",
                "<p>a &amp; b</p>"))), StandardCharsets.UTF_8);
            assertThat(out).doesNotContain("&amp;lt;");
        }
    }

    @Nested
    @DisplayName("rule 6: an incomplete source fails loudly instead of publishing a broken document")
    class Completeness {

        @Test
        void rssRefusesAChannelWithoutADescription() {
            FeedMeta bare = new FeedMeta("T", "https://example.org/", null, null, null, null, null, null, null);
            assertThatThrownBy(() -> rss.render(bare, List.of()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("feed_incomplete");
                    assertThat(e.status().is5xxServerError()).isTrue();
                });
        }

        @Test
        void rssRefusesAnItemWithNeitherTitleNorDescription() {
            FeedItem headless = new FeedItem("https://example.org/blog/h", "https://example.org/blog/h", null,
                null, null, null, PUBLISHED, UPDATED, List.of(), List.of(), List.of(), null, null, null);
            assertThatThrownBy(() -> rss.render(meta(), List.of(headless)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("title or description");
        }

        @Test
        void aFeedWithNoUpdatedAtAndNoItemsStillCarriesTheMandatoryUpdatedElement() {
            FeedMeta noBuild = new FeedMeta("T", "https://example.org/", "D", null, null, null,
                "https://example.org/feed.atom", null, null);
            // RFC 4287 §4.1.1: atom:feed MUST have exactly one atom:updated, so an empty outlet with a null
            // lastBuildAt cannot be refused — the writer falls back to the epoch rather than failing the request.
            String text = new String(atom.render(noBuild, List.of()), StandardCharsets.UTF_8);
            assertThat(text).contains("<updated>1970-01-01T00:00:00Z</updated>").doesNotContain("<entry>");
        }

        @Test
        void aFeedWithNoUpdatedAtUsesTheNewestEntryWhenThereAreEntries() {
            FeedMeta noBuild = new FeedMeta("T", "https://example.org/", "D", null, null, null,
                "https://example.org/feed.atom", null, null);
            FeedItem older = new FeedItem("https://example.org/blog/1", null, "One", null, null, null,
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-05T00:00:00Z"),
                List.of(), List.of(), List.of(), null, null, null);
            FeedItem newer = new FeedItem("https://example.org/blog/2", null, "Two", null, null, null,
                Instant.parse("2026-10-10T00:00:00Z"), Instant.parse("2026-10-15T00:00:00Z"),
                List.of(), List.of(), List.of(), null, null, null);
            String text = new String(atom.render(noBuild, List.of(older, newer)), StandardCharsets.UTF_8);
            // The feed-level <updated> is the max over the entries, not the top-level source's build stamp.
            int feedEnd = text.indexOf("<entry>");
            assertThat(text.substring(0, feedEnd)).contains("<updated>2026-10-15T00:00:00Z</updated>");
        }

        @Test
        void jsonRefusesAnItemWithoutAnId() {
            FeedItem idless = new FeedItem(null, "https://example.org/blog/i", "T", "s", null, null, PUBLISHED,
                UPDATED, List.of(), List.of(), List.of(), null, null, null);
            assertThatThrownBy(() -> json.render(meta(), List.of(idless))).isInstanceOf(ApiException.class);
        }

        @Test
        void anEmptyOutletStillSerialisesToADocumentThatValidates() {
            // "no posts yet" is a real state of a personal site; it must not be a parse error either.
            assertThatCode(() -> parse(rss.render(meta(), List.of()))).doesNotThrowAnyException();
            assertThatCode(() -> parse(atom.render(meta(), List.of()))).doesNotThrowAnyException();
            assertThat(json.render(meta(), List.of())).asString(StandardCharsets.UTF_8).contains("\"items\":[]");
        }
    }

    @Nested
    @DisplayName("dates: RSS uses RFC 822 grammar, Atom and JSON use RFC 3339")
    class Dates {

        @Test
        void rssDatesAreFourDigitYearWithNumericOffset() {
            String out = new String(rss.render(meta(), List.of(item("T", "https://example.org/blog/t", "b"))),
                StandardCharsets.UTF_8);
            assertThat(out).contains("<pubDate>Sat, 17 Oct 2026 01:02:03 +0000</pubDate>");
        }

        @Test
        void atomDatesUseTheUppercaseSeparatorAndZ() {
            String out = new String(atom.render(meta(), List.of(item("T", "https://example.org/blog/t", "b"))),
                StandardCharsets.UTF_8);
            assertThat(out).contains("<published>2026-10-17T01:02:03Z</published>")
                .contains("<updated>2026-10-18T04:05:06Z</updated>");
        }

        @Test
        void jsonDatesAreRfc3339Too() {
            String out = new String(json.render(meta(), List.of(item("T", "https://example.org/blog/t", "b"))),
                StandardCharsets.UTF_8);
            assertThat(out).contains("\"date_published\":\"2026-10-17T01:02:03Z\"");
        }
    }

    private static Element firstTag(org.w3c.dom.Document document, String name) {
        return (Element) document.getElementsByTagName(name).item(0);
    }

    /** The channel's own `<title>` comes first in a RSS document, so item assertions have to be scoped. */
    private static Element tagIn(Element parent, String name) {
        return (Element) parent.getElementsByTagName(name).item(0);
    }

    /** A parser with no network access and no leniency: namespace-aware, and it will not fetch an external DTD. */
    private static org.w3c.dom.Document parse(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setAttribute(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new AssertionError("the writer produced something a strict parser rejects", e);
        }
    }
}
