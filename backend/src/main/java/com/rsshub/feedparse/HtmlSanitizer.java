package com.rsshub.feedparse;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

/**
 * 03 §5.5: clean once, before the bytes reach the database, so the reader can trust {@code entry.content_html} and
 * the render path never has to run a sanitizer again (line 580 of that document is explicit about why — the same
 * content must not look different in two places because two code paths cleaned it differently).
 *
 * <p>Three things happen here, in this order, because each one needs the previous: the DOM is repaired and
 * inspected (tracking pixels, embed hosts, link targets), then the whitelist is applied, then relative addresses
 * become absolute against the entry's own base.
 */
public final class HtmlSanitizer {

    /**
     * The tag list is 03 §5.5's, verbatim. {@code target}/{@code rel}/{@code sandbox} are allowed as attributes
     * because {@link #prepare(Element, String)} writes them and the cleaner would otherwise strip hardening it just
     * applied. No {@code abs:*} entries: {@code preserveRelativeLinks} is off by default, so the cleaner resolves
     * every href/src against the base URI and writes the absolute form back into the real attribute.
     */
    private static final Safelist WHITELIST = new Safelist()
        .addTags("p", "br", "ul", "ol", "li", "blockquote", "pre", "code", "h1", "h2", "h3", "h4", "a", "img",
            "em", "strong", "table", "thead", "tbody", "tr", "th", "td", "span", "del", "hr", "s", "sup", "sub",
            "figure", "figcaption", "iframe")
        .addAttributes("a", "href", "title", "target", "rel")
        .addProtocols("a", "href", "http", "https", "mailto", "ftp")
        .addAttributes("img", "src", "alt", "title", "width", "height")
        .addProtocols("img", "src", "http", "https")
        .addAttributes("iframe", "src", "width", "height", "sandbox", "title")
        .addProtocols("iframe", "src", "http", "https")
        .addAttributes("code", "class")
        .addAttributes("pre", "class");

    /** The only embeds a personal reader should run. Anything else is a script with better CSS. */
    private static final Set<String> EMBED_HOSTS = Set.of(
        "youtube.com", "www.youtube.com", "m.youtube.com", "youtube.googleapis.com", "www.youtube-nocookie.com",
        "player.bilibili.com", "player.video.qiyi.com", "open.spotify.com", "archive.org", "www.google.com");

    /** Hosts that appear in a feed only to count opens (经验规则, not any spec). */
    private static final Set<String> PIXEL_HOSTS = Set.of(
        "open.mailingchimp.com", "mailchimp.com", "cdn-images.mailchimp.com", "pixel.matomo.com", "mc.yandex.ru",
        "csi.gstatic.com", "email-tracker.net", "sendinblue.com", "mailgun.com");

    private static final Document.OutputSettings OUTPUT = new Document.OutputSettings()
        // Pretty-printing would add newlines inside <pre> and around inline tags, and the reader shows this as-is.
        .prettyPrint(false)
        .escapeMode(Entities.EscapeMode.xhtml)
        .charset(StandardCharsets.UTF_8);
    private HtmlSanitizer() {
    }

    /**
     * @param baseUri what relative addresses resolve against: the entry link, else the feed URL (03 §5.5). Null
     *        leaves a relative address as it came in rather than inventing a host.
     */
    public static String clean(String html, String baseUri) {
        if (html == null || html.isBlank()) {
            return null;
        }
        // One parse, then Cleaner: Jsoup.clean() would re-parse the string this method just serialised, and the
        // prepare() step has to happen between the parse and the filter, which only the two-step API allows.
        Document fragment = Jsoup.parseBodyFragment(html, baseUri == null ? "" : baseUri);
        prepare(fragment.body());
        Document cleaned = new Cleaner(WHITELIST).clean(fragment);
        // clone(): OutputSettings caches an encoder lazily, so a shared instance would be written to by every
        // fetch thread.
        String body = cleaned.outputSettings(OUTPUT.clone()).body().html();
        // A document that was entirely disallowed serialises to ""; a null column is the honest answer.
        return body.isBlank() ? null : body;
    }

    /** Plain text for the list view and for search: no tags, no entities, single-spaced. */
    public static String text(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        // wholeText() keeps &nbsp; as U+00A0, and a search index or a snippet with a non-breaking space in it looks
        // like a broken string everywhere except in a browser.
        String text = Jsoup.parseBodyFragment(html).body().wholeText().replace('\u00A0', ' ')
            .replaceAll("[ \\t\\x0B\\f]+", " ")
            .replaceAll(" ?\\n ?", "\n").trim();
        return text.isEmpty() ? null : text;
    }

    private static void prepare(Element root) {
        // These are dropped by the whitelist anyway, but removing them first means their text cannot survive as a
        // stray paragraph of CSS or half a script body.
        root.select("script, style, link, meta, form, input, button, select, textarea, object, embed, svg").remove();

        for (Element img : root.select("img")) {
            if (isTrackingPixel(img)) {
                img.remove();
            }
        }

        for (Element frame : root.select("iframe")) {
            if (!isAllowedEmbed(frame.attr("src"))) {
                frame.remove();
                continue;
            }
            // sandbox is what makes a third-party embed safe to keep: no top-navigation, no forms. Fullscreen stays
            // off because allow-fullscreen does not exist as a token; the host page can still scroll.
            frame.attr("sandbox", "allow-scripts allow-same-origin allow-popups");
        }

        for (Element anchor : root.select("a[href]")) {
            // The reader is a separate tab, and a feed-provided link must not be able to rewrite this one.
            anchor.attr("target", "_blank");
            anchor.attr("rel", "noopener noreferrer");
        }
    }

    private static boolean isTrackingPixel(Element img) {
        String src = img.attr("src").toLowerCase(Locale.ROOT);
        if (src.isEmpty()) {
            return true;
        }
        if (src.contains("/open.aspx?") || src.contains("/track/") || src.contains("/pixel")) {
            return true;
        }
        String width = img.attr("width");
        String height = img.attr("height");
        if (("1".equals(width) || "0".equals(width) || "1".equals(height) || "0".equals(height))) {
            return true;
        }
        String host = hostOf(src);
        return host != null && PIXEL_HOSTS.contains(host);
    }

    private static boolean isAllowedEmbed(String src) {
        String host = hostOf(src);
        return host != null && EMBED_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    private static String hostOf(String url) {
        try {
            return new URI(url).getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
