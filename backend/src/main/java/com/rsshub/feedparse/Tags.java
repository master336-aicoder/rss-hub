package com.rsshub.feedparse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * Tag lookup that does not care about prefixes or case, which is what a tolerant parser needs: the same document
 * can be {@code <content:encoded>}, {@code <Content:encoded>} or {@code <e:content:encoded>} depending on which
 * PHP library wrote it, and 02 §3 counts "混合 ns" as a case to survive.
 *
 * <p>Local name means "the part after the colon", so {@code atom:id} and {@code id} match the same query. That is
 * deliberate: the format was already decided by the sniffer, so inside an Atom document a bare {@code <id>} can only
 * mean the Atom id.
 */
public final class Tags {

    private Tags() {
    }

    public static String localName(Element element) {
        String tag = element.tagName();
        int colon = tag.indexOf(':');
        return (colon >= 0 ? tag.substring(colon + 1) : tag).toLowerCase(Locale.ROOT);
    }

    public static boolean hasPrefix(Element element) {
        return element.tagName().indexOf(':') >= 0;
    }

    /** The prefix as written, lower-cased; empty when the element is unprefixed. */
    public static String prefix(Element element) {
        String tag = element.tagName();
        int colon = tag.indexOf(':');
        return colon >= 0 ? tag.substring(0, colon).toLowerCase(Locale.ROOT) : "";
    }

    /**
     * Direct children only, so an Atom {@code <title>} is never confused with a {@code <image><title>}. The name to
     * look for is lower-cased like the tag names are — callers write {@code pubDate} and {@code updatePeriod} the way
     * the spec spells them, and a case-sensitive compare would silently find nothing.
     */
    public static List<Element> children(Element parent, String localName) {
        String wanted = localName.toLowerCase(Locale.ROOT);
        List<Element> found = new ArrayList<>();
        for (Element child : parent.children()) {
            if (localName(child).equals(wanted)) {
                found.add(child);
            }
        }
        return found;
    }

    public static Element child(Element parent, String localName) {
        List<Element> found = children(parent, localName);
        return found.isEmpty() ? null : found.get(0);
    }

    /** Anywhere below, used for the few things that legally nest ({@code media:group/media:content}). */
    public static List<Element> descendants(Element parent, String localName) {
        String wanted = localName.toLowerCase(Locale.ROOT);
        Elements all = parent.getAllElements();
        List<Element> found = new ArrayList<>();
        for (Element element : all) {
            if (element != parent && localName(element).equals(wanted)) {
                found.add(element);
            }
        }
        return found;
    }

    /**
     * Text of the first matching child that has any, or null. {@code text()} rather than {@code ownText()} because a
     * publisher that puts markup inside a title still means the whole phrase, and jsoup has already resolved
     * entities/CDATA.
     *
     * <p>Every matching child is tried, not only the first: RSS allows {@code <atom:link>} next to
     * {@code <link>}, both have local name {@code link}, and the namespaced one is empty text — stopping at it is
     * how a channel loses its site address.
     */
    public static String childText(Element parent, String localName) {
        for (Element child : children(parent, localName)) {
            String value = blankToNull(child.text().trim());
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Attribute lookup that survives case: the XML parser keeps attribute names as written, and a publisher that
     * types {@code HREF="..."} would otherwise look like a link with no address.
     */
    public static String attr(Element element, String name) {
        if (element == null) {
            return null;
        }
        String exact = element.attr(name);
        if (!exact.isBlank()) {
            return exact.trim();
        }
        for (Attribute attribute : element.attributes()) {
            if (attribute.getKey().equalsIgnoreCase(name)) {
                return blankToNull(attribute.getValue().trim());
            }
        }
        return null;
    }

    /** {@code isBlank} not {@code isEmpty}: a tag that holds only a newline is a tag the publisher left empty. */
    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
