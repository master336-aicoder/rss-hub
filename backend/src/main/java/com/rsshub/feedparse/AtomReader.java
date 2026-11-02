package com.rsshub.feedparse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.nodes.Element;

/**
 * Atom (RFC 4287, obsoleted by RFC 5023). The shape differences that matter here: the address is an attribute on a
 * typed {@code <link>} rather than an element's text, dates are ISO 8601 rather than RFC 822, and the body is
 * {@code <content>} with a declared type.
 *
 * <p>RSS extensions appear inside Atom feeds constantly ({@code <media:content>}, {@code <itunes:duration>},
 * {@code <sy:updatePeriod>}), so the shared helpers in {@link EntryFields} and {@link FeedFields} do that work and
 * this class only decides which Atom element means what.
 */
final class AtomReader {

    private AtomReader() {
    }

    static NormalizedFeed read(Element root, String feedUrl) {
        String siteUrl = FeedFields.alternateUrl(Tags.children(root, "link"), feedUrl);
        List<NormalizedEntry> entries = new ArrayList<>();
        for (Element entry : Tags.children(root, "entry")) {
            entries.add(entry(entry, feedUrl));
        }
        return new NormalizedFeed(Tags.childText(root, "title"), siteUrl, FeedFields.selfUrl(root, feedUrl),
            FeedFields.text(root, "subtitle", "description", "tagline"), Tags.attr(root, "xml:lang"),
            FeedFormat.ATOM, FeedFields.ttlMinutes(root, root), entries);
    }

    private static NormalizedEntry entry(Element entry, String feedUrl) {
        String url = FeedFields.alternateUrl(Tags.children(entry, "link"), feedUrl);
        Instant updated = FlexibleDates.parse(Tags.childText(entry, "updated"));
        Instant published = FlexibleDates.parse(FeedFields.text(entry, "published", "issued", "date"));
        if (published == null) {
            // <published> is optional, and a feed that only says <updated> is saying "this has never changed" —
            // ordering the inbox needs a date, so updated stands in for it.
            published = updated;
        }
        return EntryFields.assemble(entry, feedUrl, url, published, updated, Tags.attr(entry, "xml:lang"));
    }
}
