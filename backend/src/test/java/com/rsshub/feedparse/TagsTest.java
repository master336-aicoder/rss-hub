package com.rsshub.feedparse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.Test;

/**
 * The prefix/case rules the rest of the package assumes. These run against jsoup's XML parser rather than a mock,
 * because the whole point is what jsoup's XML parser actually produces: prefixed tag names kept verbatim, and
 * mixed case kept too.
 */
class TagsTest {

    private static Element parse(String xml) {
        return Jsoup.parse(xml, "https://example.com/feed", Parser.xmlParser()).firstElementChild();
    }

    @Test
    void keepsThePrefixAndTheLocalNameSeparately() {
        Element root = parse("<rss><item><dc:creator>Ann</dc:creator><content:encoded>x</content:encoded></item></rss>");
        Element item = Tags.child(root, "item");
        assertThat(Tags.localName(item)).isEqualTo("item");
        assertThat(Tags.hasPrefix(item)).isFalse();
        assertThat(Tags.prefix(item)).isEmpty();
        assertThat(Tags.localName(Tags.child(item, "creator"))).isEqualTo("creator");
        assertThat(Tags.prefix(Tags.child(item, "creator"))).isEqualTo("dc");
        assertThat(Tags.prefix(Tags.child(item, "encoded"))).isEqualTo("content");
    }

    @Test
    void matchesByLocalNameSoAdeclaredOrUndeclaredPrefixDoesNotMatter() {
        Element root = parse("<feed><entry><atom:id>i1</atom:id><dc:title>T</dc:title><id>ignored</id></entry></feed>");
        Element entry = Tags.child(root, "entry");
        assertThat(Tags.localName(Tags.child(entry, "id"))).isEqualTo("id");
        assertThat(Tags.childText(entry, "id")).isEqualTo("i1");
        assertThat(Tags.childText(entry, "title")).isEqualTo("T");
    }

    @Test
    void childrenAreDirectOnlyAndInDocumentOrder() {
        Element root = parse("<rss><channel><item>a<link>one</link></item><item>b</item>"
            + "<image><title>not an item title</title></image></channel></rss>");
        Element channel = Tags.child(root, "channel");
        List<Element> items = Tags.children(channel, "item");
        assertThat(items).hasSize(2);
        assertThat(Tags.childText(items.get(0), "link")).isEqualTo("one");
        // <image><title> is not a channel title; direct-only matching is what keeps the two apart.
        assertThat(Tags.childText(channel, "title")).isNull();
    }

    @Test
    void descendantsFindTheThingsThatLegallyNest() {
        Element root = parse("<rss><item><media:group><media:content url=\"m1\"/><media:content url=\"m2\"/>"
            + "</media:group></item></rss>");
        Element item = Tags.child(root, "item");
        assertThat(Tags.descendants(item, "content")).hasSize(2);
        assertThat(Tags.descendants(item, "group")).hasSize(1);
    }

    @Test
    void attributeLookupIgnoresCaseBecauseTheXmlParserPreservesIt() {
        Element root = parse("<feed><entry><LINK HREF=\"https://example.com/a\" REL=\"alternate\"/></entry></feed>");
        Element link = Tags.descendants(root, "link").get(0);
        assertThat(Tags.attr(link, "href")).isEqualTo("https://example.com/a");
        assertThat(Tags.attr(link, "rel")).isEqualTo("alternate");
        assertThat(Tags.attr(link, "missing")).isNull();
    }

    @Test
    void blankTextAndBlankAttributesAreBothNull() {
        Element root = parse("<rss><item><title>   </title><guid></guid><link href=\"  \"/></item></rss>");
        Element item = Tags.child(root, "item");
        assertThat(Tags.childText(item, "title")).isNull();
        assertThat(Tags.childText(item, "guid")).isNull();
        assertThat(Tags.attr(Tags.child(item, "link"), "href")).isNull();
    }

    @Test
    void textOfAChildSurvivesMarkupInsideIt() {
        Element root = parse("<rss><item><title>Hello <b>World</b> &amp; friends</title></item></rss>");
        Element item = Tags.child(root, "item");
        assertThat(Tags.childText(item, "title")).isEqualTo("Hello World & friends");
    }

    @Test
    void selfClosingAndEmptyElementsAreBothHandled() {
        Element root = parse("<feed><entry><id/></entry><entry><id>x</id></entry></feed>");
        List<Element> entries = Tags.children(root, "entry");
        assertThat(Tags.childText(entries.get(0), "id")).isNull();
        assertThat(Tags.child(entries.get(0), "id")).isNotNull();
        assertThat(Tags.childText(entries.get(1), "id")).isEqualTo("x");
    }
}
