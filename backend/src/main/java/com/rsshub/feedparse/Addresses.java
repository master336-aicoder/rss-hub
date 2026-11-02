package com.rsshub.feedparse;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * 03 §5.5 and {@code rss-quickref.md:118}: a feed that is served from a different host than the site it talks
 * about has no single correct base URL, and relative addresses inside it are therefore ambiguous. The rule this
 * project uses is the one readers converged on — resolve against the entry's own link when there is one, otherwise
 * the feed's URL — and the entry's link becomes absolute in the same breath, because {@code entry.url} is what the
 * UI and the re-distribution feed both show.
 *
 * <p>Never throws. An address the JDK cannot parse is returned as it came in; a feed full of {@code http://} with
 * spaces is the publisher's problem, and guessing would silently point readers at the wrong host.
 */
public final class Addresses {

    private Addresses() {
    }

    public static String absolute(String base, String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        String value = trimInvisible(href.trim());
        if (value.startsWith("//") && base != null) {
            String scheme = schemeOf(base);
            return scheme == null ? value : scheme + ":" + value;
        }
        if (value.startsWith("?") && base != null && !base.isBlank()) {
            // java.net.URI.resolve implements RFC 2396, which throws the base path away for a query-only
            // reference; feeds use "?utm=rss" to mean "this same article, with a tag".
            int query = base.indexOf('?');
            return (query < 0 ? base : base.substring(0, query)) + value;
        }
        if (isAbsolute(value)) {
            return value;
        }
        if (base == null || base.isBlank()) {
            return value;
        }
        try {
            // URI.resolve is the same rule an HTML base tag applies, and it is the only part here that handles
            // "../", "./" and "?query" correctly, so it stays even though it throws on junk input.
            return new URI(base).resolve(value).normalize().toASCIIString();
        } catch (URISyntaxException | IllegalArgumentException | IllegalStateException e) {
            return value;
        }
    }

    private static boolean isAbsolute(String value) {
        try {
            URI uri = new URI(value);
            return uri.isAbsolute();
        } catch (URISyntaxException e) {
            // "http://example.com/first second" is not a valid URI but is obviously meant as one.
            return value.matches("(?i)^[a-z][a-z0-9+.-]*:/{1,2}\\S.*");
        }
    }

    /**
     * Used only for the RSS {@code <guid isPermaLink="true">} shortcut: a guid that starts with http(s) is the
     * article address, and anything else ("tag:example,2020:/post/1") must not become a clickable link.
     */
    public static boolean isHttp(String value) {
        return value != null && value.matches("(?i)^https?://\\S.*");
    }

    private static String schemeOf(String base) {
        try {
            String scheme = new URI(base).getScheme();
            return scheme == null ? null : scheme.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * A zero-width space before a URL is a real thing Chinese and Korean publishers do to defeat link previews, and
     * it breaks both {@code URI} parsing and the copy-to-clipboard button.
     */
    private static String trimInvisible(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isInvisible(value.charAt(start))) {
            start++;
        }
        while (end > start && isInvisible(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isInvisible(char c) {
        return c == '\u200B' || c == '\uFEFF' || c == '\u200E' || c == '\u200F' || c == '\u00A0';
    }
}
