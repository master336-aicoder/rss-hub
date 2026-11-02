package com.rsshub.editorial;

import java.util.List;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Entities;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

/**
 * Markdown to the HTML that gets stored (03 §6.3: the feed needs a body, and a feed cannot ship Markdown source to a
 * reader). Two stages, in this order, because neither is sufficient alone: CommonMark decides what the author meant,
 * the whitelist decides what is safe to serve.
 *
 * <p>The whitelist is a local copy of 03 §5.5's rather than a call into {@code feedparse.HtmlSanitizer}: the input is
 * my own writing, not an untrusted publisher's bytes, and the tag list differs on purpose — a post needs {@code <h5>},
 * {@code <video>} and {@code <details>}, and a consumer of someone else's feed has no business getting those.
 * Keeping the two separate also means the render path stays unreachable from the parse path, which is 02 §3 rule 1.
 *
 * <p>Tables and strikethrough are GFM extensions, not CommonMark: they are enabled because "compare two options in a
 * table" is the single most common thing a technical note does.
 */
public final class MarkdownRenderer {

    private static final List<Extension> EXTENSIONS =
        List.of(TablesExtension.create(), StrikethroughExtension.create());

    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();

    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
        .extensions(EXTENSIONS)
        // Raw HTML from the author reaches the whitelist instead of being escaped at parse time. That is a
        // deliberate trade: escaping would also refuse <details> and <video>, which posts legitimately need, so
        // the sanitizer — not the parser — is the security boundary here.
        .build();

    private static final Safelist WHITELIST = Safelist.none()
        .addTags("p", "br", "hr", "ul", "ol", "li", "blockquote", "pre", "code", "em", "strong", "del", "a", "img",
            "h1", "h2", "h3", "h4", "h5", "h6", "table", "thead", "tbody", "tr", "th", "td", "span", "sup", "sub",
            "figure", "figcaption", "video", "source", "details", "summary")
        .addAttributes("a", "href", "title")
        .addProtocols("a", "href", "http", "https", "mailto")
        .addAttributes("img", "src", "alt", "title", "width", "height")
        .addProtocols("img", "src", "http", "https")
        .addAttributes("video", "src", "controls", "poster", "width", "height")
        .addProtocols("video", "src", "http", "https")
        .addAttributes("source", "src", "type")
        .addProtocols("source", "src", "http", "https")
        .addAttributes("code", "class")
        .addAttributes("pre", "class")
        .addAttributes("th", "align")
        .addAttributes("td", "align")
        .addAttributes("details", "open")
        .addAttributes("del", "cite");

    private MarkdownRenderer() {
    }

    /** The stored body. Never null: an empty post renders to an empty string, which is a valid answer. */
    public static String toHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String raw = RENDERER.render(PARSER.parse(markdown));
        Document fragment = Jsoup.parseBodyFragment(raw);
        Document cleaned = new Cleaner(WHITELIST).clean(fragment);
        return cleaned.outputSettings(new Document.OutputSettings()
            .prettyPrint(false)
            .escapeMode(Entities.EscapeMode.xhtml)
            .charset("UTF-8")).body().html();
    }

    /**
     * A summary for a post written without one. The first paragraph is what an outline should hold; falling back to
     * the first characters of anything is how a feed ends up advertising a code block.
     */
    public static String summarize(String html, int maxLength) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document parsed = Jsoup.parseBodyFragment(html);
        parsed.select("script, style, pre").remove();
        String text = parsed.body().text().replace('\u00A0', ' ').trim();
        if (text.length() <= maxLength) {
            return text;
        }
        // Cut on a word boundary so a truncated summary does not end half-way through an identifier.
        int cut = text.lastIndexOf(' ', maxLength);
        return (cut > maxLength / 2 ? text.substring(0, cut) : text.substring(0, maxLength)).strip() + "…";
    }
}
