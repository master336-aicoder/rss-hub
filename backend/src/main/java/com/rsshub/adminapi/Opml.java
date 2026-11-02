package com.rsshub.adminapi;

import com.rsshub.feedstore.FeedRepository.Subscription;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

/**
 * OPML 2.0 in and out (FR-2.6, 01 "可迁移"). Exit is the point of self-hosting, so the export is written for
 * Miniflux and FreshRSS to read rather than only for us: nested {@code <outline>} per group, {@code xmlUrl} plus
 * {@code htmlUrl}, and nothing that depends on our own ids.
 *
 * <p>The reader is forgiving about nesting — plenty of exports put every outline flat under one head — and drops
 * attributes it does not know. What it does not tolerate is a missing {@code xmlUrl}, because an outline without a
 * feed URL is a folder, and importing it as a subscription would create an empty feed row that fails forever.
 */
final class Opml {

    private Opml() {
    }

    /** One parsed outline: the feed URL, its own title, and the group it sat under. */
    record Parsed(String xmlUrl, String title, String groupName) {
    }

    static String export(List<Subscription> subscriptions) {
        StringBuilder out = new StringBuilder(256);
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            .append("<opml version=\"2.0\">\n  <head><title>")
            .append(escape("RSS Hub subscriptions"))
            .append("</title></head>\n  <body>\n");

        // Insertion order matters: the repository already sorted by group, and a HashMap would shuffle the outlines
        // into an order that changes between two exports of the same database.
        Map<String, List<Subscription>> byGroup = new LinkedHashMap<>();
        for (Subscription subscription : subscriptions) {
            byGroup.computeIfAbsent(subscription.groupName() == null ? "" : subscription.groupName(),
                key -> new ArrayList<>()).add(subscription);
        }

        for (Map.Entry<String, List<Subscription>> group : byGroup.entrySet()) {
            if (group.getKey().isEmpty()) {
                // Ungrouped subscriptions stay at the top level, which is what every importer expects to see.
                group.getValue().forEach(subscription -> out.append(outline(subscription, "    ")));
                continue;
            }
            String name = escape(group.getKey());
            out.append("    <outline text=\"").append(name).append("\" title=\"").append(name).append("\">\n");
            group.getValue().forEach(subscription -> out.append(outline(subscription, "      ")));
            out.append("    </outline>\n");
        }
        return out.append("  </body>\n</opml>\n").toString();
    }

    /**
     * The five XML predefines, plus dropping the C0 control range, which has no valid representation in a document
     * at all. Non-ASCII is deliberately left as UTF-8 bytes: jsoup's entity coder would turn a Chinese group name
     * into {@code &#31085;} sequences, which parses identically but makes the file an operator downloads unreadable.
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> {
                    if (c >= 0x20) {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String outline(Subscription subscription, String indent) {
        String title = subscription.title() == null || subscription.title().isBlank()
            ? subscription.xmlUrl()
            : subscription.title();
        StringBuilder element = new StringBuilder(indent).append("<outline text=\"").append(escape(title))
            .append("\" title=\"").append(escape(title))
            .append("\" type=\"rss\" xmlUrl=\"").append(escape(subscription.xmlUrl())).append('"');
        if (subscription.htmlUrl() != null && !subscription.htmlUrl().isBlank()) {
            element.append(" htmlUrl=\"").append(escape(subscription.htmlUrl())).append('"');
        }
        return element.append("/>\n").toString();
    }

    /**
     * Reads every {@code outline} carrying an {@code xmlUrl}, grouping by the nearest titled ancestor. Two levels is
     * what exporters emit in practice, and {@code feed.group_name} is one column, so a deeper path flattens to its
     * immediate parent rather than inventing a hierarchy the schema cannot store.
     */
    static List<Parsed> parse(String xml) {
        if (xml == null || xml.isBlank()) {
            return List.of();
        }
        // The XML parser, not the HTML one: jsoup's HTML reader lower-cases tag and attribute names, and xmlUrl is
        // exactly the mixed-case attribute this selector matches on.
        Elements outlines = Jsoup.parse(xml, "", Parser.xmlParser()).select("outline[xmlUrl]");
        List<Parsed> parsed = new ArrayList<>();
        for (Element outline : outlines) {
            String url = outline.attr("xmlUrl").trim();
            if (url.isEmpty()) {
                continue;
            }
            parsed.add(new Parsed(url, blankToNull(outline.attr("title"), outline.attr("text")),
                groupNameOf(outline)));
        }
        return parsed;
    }

    private static String groupNameOf(Element outline) {
        for (Element parent = outline.parent(); parent != null; parent = parent.parent()) {
            if (!parent.tagName().equalsIgnoreCase("outline")) {
                continue;
            }
            String title = blankToNull(parent.attr("title"), parent.attr("text"));
            if (title != null) {
                return title;
            }
        }
        return null;
    }

    /** OPML exporters split between {@code title} and {@code text}; some write only one of them. */
    private static String blankToNull(String title, String text) {
        if (title != null && !title.isBlank()) {
            return title.trim();
        }
        return text == null || text.isBlank() ? null : text.trim();
    }
}
