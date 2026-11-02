package com.rsshub.feedparse;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jsoup.nodes.Element;

/**
 * The feed-level half of the mapping: what the subscription is called, where it points, how often it wants to be
 * asked. Split from {@link EntryFields} because a publisher puts these on the channel and the reader has to know
 * which channel — RSS uses {@code <channel>}, Atom uses the root {@code <feed>}, RDF splits them between the two.
 */
final class FeedFields {

    /**
     * {@code sy:updatePeriod} is the syndication module's spelling of the same idea as {@code <ttl>}, and it multiplies
     * a period by {@code sy:updateFrequency}. The module's own table names the periods as adverbs ("hourly",
     * "daily"), and feeds that never read it write the noun instead, so both spellings are listed. Stripping a
     * trailing "ly" is not enough: "daily" would become "dai".
     */
    private static final Map<String, Integer> SY_MINUTES = Map.ofEntries(
        Map.entry("minute", 1), Map.entry("minutely", 1),
        Map.entry("hour", 60), Map.entry("hourly", 60),
        Map.entry("day", 1440), Map.entry("daily", 1440),
        Map.entry("week", 10_080), Map.entry("weekly", 10_080),
        Map.entry("month", 43_200), Map.entry("monthly", 43_200),
        Map.entry("year", 525_600), Map.entry("yearly", 525_600));

    private FeedFields() {
    }

    /** First child whose text is not blank, tried in the order given. */
    static String text(Element scope, String... localNames) {
        for (String localName : localNames) {
            String value = Tags.childText(scope, localName);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 03 §4.1: a feed's own advertised interval, in minutes, or null when it advertises nothing and the fetcher's
     * default applies. Only positive values survive — {@code <ttl>0</ttl>} is a publisher asking for an impossible
     * poll rate, and {@code FetchPolicy.intervalFor} already refuses to go below its floor.
     */
    static Integer ttlMinutes(Element scope, Element root) {
        Integer direct = positiveInt(Tags.childText(scope, "ttl"));
        if (direct != null) {
            return direct;
        }
        for (Element scopeCandidate : List.of(scope, root)) {
            Integer fromSy = syMinutes(scopeCandidate);
            if (fromSy != null) {
                return fromSy;
            }
        }
        return null;
    }

    private static Integer syMinutes(Element scope) {
        String period = Tags.childText(scope, "updatePeriod");
        if (period == null) {
            return null;
        }
        Integer minutesPerPeriod = SY_MINUTES.get(period.toLowerCase(Locale.ROOT).trim());
        if (minutesPerPeriod == null) {
            return null;
        }
        Integer frequency = positiveInt(Tags.childText(scope, "updateFrequency"));
        return minutesPerPeriod * (frequency == null ? 1 : frequency);
    }

    /**
     * The self link, when the publisher declares one. It is not what we subscribed to, so it only ever overrides the
     * subscription URL for {@code feed.feed_url} (which the UI shows and the re-published feed advertises).
     */
    static String selfUrl(Element scope, String fallback) {
        for (Element link : Tags.children(scope, "link")) {
            if ("self".equalsIgnoreCase(link.attr("rel"))) {
                String href = Addresses.absolute(fallback, Tags.attr(link, "href"));
                if (href != null) {
                    return href;
                }
            }
        }
        return fallback;
    }

    /**
     * Atom puts the address of the thing itself on a {@code <link>} with no {@code rel} (or {@code rel=alternate});
     * a {@code rel=self} or {@code rel=enclosure} link must never be mistaken for it, which is how a feed of
     * subscriber-only JSON ends up as every entry's URL.
     */
    static String alternateUrl(List<Element> links, String base) {
        String firstUntyped = null;
        for (Element link : links) {
            String rel = link.attr("rel").trim().toLowerCase(Locale.ROOT);
            if (rel.isEmpty() || "alternate".equals(rel)) {
                String href = Addresses.absolute(base, Tags.attr(link, "href"));
                if (href == null) {
                    continue;
                }
                if (firstUntyped == null) {
                    firstUntyped = href;
                }
                // type=text/html is what a reader should open; a link that says so wins over one that does not.
                String type = Tags.attr(link, "type");
                if (type == null || type.toLowerCase(Locale.ROOT).startsWith("text/html")) {
                    return href;
                }
            }
        }
        return firstUntyped;
    }

    private static Integer positiveInt(String value) {
        if (value == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
