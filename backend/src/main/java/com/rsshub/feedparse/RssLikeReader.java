package com.rsshub.feedparse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.nodes.Element;

/**
 * RSS 2.0 and RSS 1.0 (RDF) in one reader, because after the sniffer has decided which one it is, the mapping is the
 * same: {@code <item>} elements with a text {@code <link>}, an RFC 822 date, and Dublin Core as the fallback for
 * everything the base format never defined. 03 §5.2 groups them together for the same reason.
 *
 * <p>Nothing here throws on a feed that is merely poor. A channel with no title, or items with no dates, still
 * produces entries — the alternative is that the operator sees "parse error" for a subscription that is 95% fine,
 * and the fetch layer already has a distinct error for "this is not a feed at all".
 */
final class RssLikeReader {

    private RssLikeReader() {
    }

    static NormalizedFeed read(Element root, String feedUrl, FeedFormat format) {
        Element channel = Tags.child(root, "channel");
        if (channel == null) {
            throw new FeedParseException(format.stored() + " document has no <channel> element");
        }
        String siteUrl = Addresses.absolute(feedUrl, Tags.childText(channel, "link"));
        List<NormalizedEntry> entries = new ArrayList<>();
        for (Element item : itemsOf(root, channel)) {
            entries.add(entry(item, feedUrl));
        }
        return new NormalizedFeed(Tags.childText(channel, "title"), siteUrl,
            FeedFields.selfUrl(channel, feedUrl), FeedFields.text(channel, "description", "tagline", "subtitle"),
            FeedFields.text(channel, "language"), format, FeedFields.ttlMinutes(channel, root), entries);
    }

    /**
     * RSS 2.0 nests {@code <item>} inside {@code <channel>}; RSS 1.0 puts them as siblings of it, directly under
     * {@code <rdf:RDF>}. Whichever the document did not use is the fallback, so a feed that mixes the two shapes —
     * which WordPress plugins have shipped — still yields its entries.
     */
    private static List<Element> itemsOf(Element root, Element channel) {
        List<Element> nested = Tags.children(channel, "item");
        return nested.isEmpty() ? Tags.children(root, "item") : nested;
    }

    private static NormalizedEntry entry(Element item, String feedUrl) {
        String url = EntryFields.url(item, feedUrl);
        // dc:date is the RDF era's pubDate, and lastBuildDate is a channel-level date that lazy publishers copy into
        // every item; both are better than no date, and FlexibleDates decides whether either is readable.
        Instant published = FlexibleDates.parse(
            FeedFields.text(item, "pubDate", "date", "issued", "lastBuildDate"));
        Instant updated = FlexibleDates.parse(FeedFields.text(item, "modified", "updated"));
        return EntryFields.assemble(item, feedUrl, url, published, updated, Tags.childText(item, "language"));
    }
}
