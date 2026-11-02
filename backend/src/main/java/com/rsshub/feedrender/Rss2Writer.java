package com.rsshub.feedrender;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * RSS 2.0 (03 §6.3, column 1). The output rules with unit tests behind them are the seven in that table; this writer
 * is where rules 1, 2, 3, 4, 5 and 6 are enforced for the format that still dominates subscriptions.
 *
 * <p>Namespace prefixes are declared on the root element even for optional ones (rule 3, doc/rss-quickref.md:143),
 * because an undeclared prefix is a document that fails to parse rather than a document with a missing feature.
 */
@Component
public class Rss2Writer implements FeedWriter {

    private static final String NS_ATOM = "http://www.w3.org/2005/Atom";
    private static final String NS_DC = "http://purl.org/dc/elements/1.1/";
    private static final String NS_CONTENT = "http://purl.org/rss/1.0/modules/content/";
    private static final String NS_SYNDICATION = "http://purl.org/rss/1.0/modules/syndication/";

    @Override
    public FeedFormat format() {
        return FeedFormat.RSS;
    }

    @Override
    public byte[] render(FeedMeta meta, List<FeedItem> items) {
        String title = required("channel title", meta.title());
        String link = required("channel link", meta.siteUrl());
        String description = required("channel description", meta.description());

        StringBuilder xml = new StringBuilder(4096);
        xml.append(XML_DECLARATION);
        xml.append("<rss version=\"2.0\"")
            .append(" xmlns:atom=\"").append(NS_ATOM).append('"')
            .append(" xmlns:dc=\"").append(NS_DC).append('"')
            .append(" xmlns:content=\"").append(NS_CONTENT).append('"')
            .append(" xmlns:sy=\"").append(NS_SYNDICATION).append('"')
            .append(">\n");
        xml.append("<channel>\n");
        xml.append(EscapeUtil.element("title", title));
        xml.append(EscapeUtil.element("link", link));
        xml.append(EscapeUtil.element("description", description));
        xml.append(EscapeUtil.element("language", meta.language()));
        if (meta.selfUrl() != null) {
            // RSS 2.0 has no self link of its own; the RSS 1.0/2.0 best-practices profile allows an Atom one, and a
            // reader that is pointed at /feed.rss?token=x can otherwise re-derive a different URL for the same feed.
            xml.append("<atom:link href=\"").append(EscapeUtil.attribute(meta.selfUrl()))
                .append("\" rel=\"self\" type=\"application/rss+xml\"/>\n");
        }
        if (meta.imageUrl() != null && !meta.imageUrl().isBlank()) {
            // <image> requires url, title and link in that order; three children, no attributes.
            xml.append("<image>")
                .append(EscapeUtil.element("url", meta.imageUrl()))
                .append(EscapeUtil.element("title", title))
                .append(EscapeUtil.element("link", link))
                .append("</image>\n");
        }
        if (meta.ttlMinutes() != null) {
            xml.append(EscapeUtil.element("ttl", String.valueOf(meta.ttlMinutes())));
        }
        // lastBuildDate is omitted rather than set to now(): a value that changes on every request tells a poller
        // the feed moved when it did not, which is exactly what the ETag exists to prevent.
        xml.append(EscapeUtil.element("lastBuildDate", FeedDates.rfc822(meta.lastBuildAt())));
        if (meta.generator() != null) {
            xml.append(EscapeUtil.element("generator", meta.generator()));
        }

        for (FeedItem item : items) {
            appendItem(xml, item);
        }
        xml.append("</channel>\n</rss>\n");
        return FeedWriter.utf8(xml.toString());
    }

    private void appendItem(StringBuilder xml, FeedItem item) {
        String id = required("item guid", item.id());
        // RSS 2.0's content model: an item needs a title or a description, per the format spec's own wording
        // (doc/rss-guide.md:267). A feed of link-only items is legal but useless, so fail loudly instead.
        if ((item.title() == null || item.title().isBlank())
            && (item.description() == null || item.description().isBlank())) {
            throw FeedWriter.incomplete(format(), "item title or description", id);
        }

        xml.append("<item>\n");
        xml.append(EscapeUtil.element("title", item.title()));
        xml.append(EscapeUtil.element("link", item.url()));
        xml.append("<guid isPermaLink=\"").append(FeedWriter.isUrl(id) ? "true" : "false").append("\">")
            .append(EscapeUtil.text(id))
            .append("</guid>\n");
        xml.append(EscapeUtil.element("pubDate", FeedDates.rfc822(item.publishedAt())));
        // The body of the rule-2 argument: description carries the summary as escaped HTML, content:encoded the full
        // body inside CDATA. Never the same string in both, never HTML in description and plain text in content.
        xml.append(EscapeUtil.element("description", item.description()));
        if (item.contentHtml() != null && !item.contentHtml().isBlank()) {
            xml.append("<content:encoded>").append(EscapeUtil.cdata(item.contentHtml()))
                .append("</content:encoded>\n");
        }
        for (String author : item.authors()) {
            xml.append(EscapeUtil.element("dc:creator", author));
        }
        for (String tag : item.tags()) {
            xml.append(EscapeUtil.element("category", tag));
        }
        for (FeedItem.Enclosure enclosure : item.enclosures()) {
            appendEnclosure(xml, enclosure);
        }
        if (item.sourceUrl() != null) {
            xml.append("<source url=\"").append(EscapeUtil.attribute(item.sourceUrl())).append("\">")
                .append(EscapeUtil.text(item.sourceTitle() == null ? item.sourceUrl() : item.sourceTitle()))
                .append("</source>\n");
        }
        xml.append("</item>\n");
    }

    /**
     * {@code url}, {@code length} and {@code type} are all required by the DTD, and {@code type} has to be a media
     * type rather than a guess. A missing length is omitted as an attribute — a wrong one is worse than none, because
     * readers reserve the announced number of bytes (doc/rss-guide.md:507).
     */
    private void appendEnclosure(StringBuilder xml, FeedItem.Enclosure enclosure) {
        xml.append("<enclosure url=\"").append(EscapeUtil.attribute(enclosure.url()))
            .append("\" type=\"").append(EscapeUtil.attribute(required("enclosure type", enclosure.type()))).append('"');
        if (enclosure.length() != null) {
            xml.append(" length=\"").append(enclosure.length()).append('"');
        }
        xml.append("/>\n");
    }
}
