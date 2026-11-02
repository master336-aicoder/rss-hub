package com.rsshub.feedrender;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Atom 1.0 (03 §6.3, column 2; RFC 4287). Two constraints drive the shape:
 *
 * <p>1. The feed element must hold exactly one each of {@code id}, {@code title} and {@code updated}. The feed's
 * {@code id} is its permanent self URL — not the site URL, which is what people get wrong first.
 *
 * <p>2. An entry with no {@code content} must have a {@code rel="alternate"} link, and inline HTML content needs a
 * {@code type="html"} attribute plus a sibling {@code summary}. This writer always emits the alternate link and
 * always emits a summary, so both branches are legal without conditional code.
 */
@Component
public class AtomWriter implements FeedWriter {

    private static final String NS_ATOM = "http://www.w3.org/2005/Atom";

    @Override
    public FeedFormat format() {
        return FeedFormat.ATOM;
    }

    @Override
    public byte[] render(FeedMeta meta, List<FeedItem> items) {
        String title = required("feed title", meta.title());
        String self = required("feed self URL (atom:id)", meta.selfUrl());
        // RFC 4287 §4.1.1: an empty feed still needs <updated>. FeedMeta documents lastBuildAt as nullable because
        // RSS omits the header; here we take the newest entry, and for a genuinely empty outlet fall back to epoch.
        Instant updated = feedUpdated(meta, items);
        String updatedText = required("feed updated", FeedDates.rfc3339(updated));

        StringBuilder xml = new StringBuilder(4096);
        xml.append(XML_DECLARATION);
        xml.append("<feed xmlns=\"").append(NS_ATOM).append('"');
        if (meta.language() != null) {
            // RFC 4287 attaches language per element, but the feed-level xml:lang is the one readers such as
            // FreshRSS act on, and it is an attribute, so it belongs on the root.
            xml.append(" xml:lang=\"").append(EscapeUtil.attribute(meta.language())).append('"');
        }
        xml.append(">\n");
        xml.append(EscapeUtil.element("id", self));
        xml.append(EscapeUtil.element("title", title));
        xml.append(EscapeUtil.element("updated", updatedText));
        if (meta.siteUrl() != null) {
            xml.append("<link rel=\"alternate\" type=\"text/html\" href=\"")
                .append(EscapeUtil.attribute(meta.siteUrl())).append("\"/>\n");
        }
        xml.append("<link rel=\"self\" type=\"application/atom+xml\" href=\"")
            .append(EscapeUtil.attribute(self)).append("\"/>\n");
        xml.append(EscapeUtil.element("subtitle", meta.description()));
        xml.append(EscapeUtil.element("logo", meta.imageUrl()));
        xml.append(EscapeUtil.element("generator", meta.generator()));

        for (FeedItem item : items) {
            appendEntry(xml, item);
        }
        xml.append("</feed>\n");
        return FeedWriter.utf8(xml.toString());
    }

    private void appendEntry(StringBuilder xml, FeedItem item) {
        String id = required("entry id", item.id());
        String title = required("entry title", item.title());
        String updated = required("entry updated", FeedDates.rfc3339(item.updatedOrPublished()));

        xml.append("<entry>\n");
        xml.append(EscapeUtil.element("id", id));
        xml.append(EscapeUtil.element("title", title));
        xml.append(EscapeUtil.element("updated", updated));
        xml.append(EscapeUtil.element("published", FeedDates.rfc3339(item.publishedAt())));
        xml.append("<link rel=\"alternate\" type=\"text/html\" href=\"")
            .append(EscapeUtil.attribute(item.url() == null ? id : item.url())).append("\"/>\n");
        for (String author : item.authors()) {
            xml.append("<author>").append(EscapeUtil.element("name", author)).append("</author>\n");
        }
        for (String tag : item.tags()) {
            xml.append("<category term=\"").append(EscapeUtil.attribute(tag)).append("\"/>\n");
        }
        for (FeedItem.Enclosure enclosure : item.enclosures()) {
            xml.append("<link rel=\"enclosure\" href=\"").append(EscapeUtil.attribute(enclosure.url()))
                .append("\" type=\"").append(EscapeUtil.attribute(required("enclosure type", enclosure.type())))
                .append('"');
            if (enclosure.length() != null) {
                xml.append(" length=\"").append(enclosure.length()).append('"');
            }
            xml.append("/>\n");
        }
        if (item.sourceUrl() != null) {
            // RFC 4287 §4.2.11: atom:source may hold the metadata of the feed an entry was copied out of, and MUST
            // NOT repeat xml:lang/atom:base. Only id/title/link go in.
            xml.append("<source>")
                .append(EscapeUtil.element("id", item.sourceUrl()))
                .append(EscapeUtil.element("title", item.sourceTitle() == null ? item.sourceUrl() : item.sourceTitle()))
                .append("</source>\n");
        }
        String summary = item.description() == null ? PlainText.preview(item.contentHtml()) : item.description();
        xml.append("<summary type=\"html\">").append(EscapeUtil.text(summary)).append("</summary>\n");
        if (item.contentHtml() != null && !item.contentHtml().isBlank()) {
            xml.append("<content type=\"html\">").append(EscapeUtil.cdata(item.contentHtml()))
                .append("</content>\n");
        }
        xml.append("</entry>\n");
    }

    private static Instant feedUpdated(FeedMeta meta, List<FeedItem> items) {
        if (meta.lastBuildAt() != null) {
            return meta.lastBuildAt();
        }
        Instant newest = null;
        for (FeedItem item : items) {
            Instant candidate = item.updatedOrPublished();
            if (candidate != null && (newest == null || candidate.isAfter(newest))) {
                newest = candidate;
            }
        }
        return newest == null ? Instant.EPOCH : newest;
    }
}
