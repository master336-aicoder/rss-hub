package com.rsshub.feedparse;

import com.rsshub.common.Hashing;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.jsoup.nodes.Entities;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * JSON Feed 1.0 and 1.1. 03 §5.2 asks which version the document claims; the answer only changes field names, and
 * this reader accepts both spellings of every field it maps, so the version is checked for sanity and never
 * branched on.
 *
 * <p>Only {@code items} has to be there, and only {@code id} inside an item; everything else is read when present
 * and left null when not.
 */
final class JsonFeedReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Mapped by this reader, or deliberately dropped, so it must not also appear in {@code extras}. */
    private static final List<String> KNOWN = List.of("id", "url", "external_url", "title", "summary",
        "content_html", "content_text", "content", "image", "banner_image", "date_published", "date_modified",
        "language", "authors", "author", "contributor", "contributors", "tags", "categories", "attachments",
        "hidden");

    private JsonFeedReader() {
    }

    static NormalizedFeed read(String json, String feedUrl) {
        JsonNode root = parse(json);
        String version = string(root.path("version"));
        if (version == null || !version.contains("jsonfeed.org")) {
            throw new FeedParseException("JSON document does not claim a jsonfeed.org version");
        }
        String language = string(root.path("language"));
        List<NormalizedEntry> entries = new ArrayList<>();
        for (JsonNode item : nodes(root.path("items"))) {
            entries.add(entry(item, feedUrl, language));
        }
        String advertisedSelf = Addresses.absolute(feedUrl, string(root.path("feed_url")));
        return new NormalizedFeed(string(root.path("title")),
            Addresses.absolute(feedUrl, string(root.path("home_page_url"))),
            advertisedSelf == null ? feedUrl : advertisedSelf, string(root.path("description")), language,
            FeedFormat.JSON, null, entries);
    }

    private static NormalizedEntry entry(JsonNode item, String feedUrl, String feedLanguage) {
        String url = Addresses.absolute(feedUrl, string(item.path("url")));
        if (url == null) {
            url = Addresses.absolute(feedUrl, string(item.path("external_url")));
        }
        String base = url == null ? feedUrl : url;
        Instant published = FlexibleDates.parse(string(item.path("date_published")));
        Instant updated = FlexibleDates.parse(string(item.path("date_modified")));
        String title = string(item.path("title"));
        EntryFields.Id id = id(item, url, title, published, feedUrl);
        String language = string(item.path("language"));
        return new NormalizedEntry(id.value(), id.source(), url, title,
            HtmlSanitizer.clean(string(item.path("summary")), base), body(item, base), authors(item),
            tags(item), attachments(item, feedUrl), published, updated,
            language == null ? feedLanguage : language, extras(item));
    }

    /** {@code content_html} is markup; {@code content_text} is plain text that must be escaped before it is one. */
    private static String body(JsonNode item, String base) {
        String html = string(item.path("content_html"));
        if (html == null) {
            String text = string(item.path("content_text"));
            if (text != null) {
                html = Entities.escape(text);
            } else if (item.has("content")) {
                // Neither 1.0 nor 1.1 defines a bare "content" key, but hand-written generators use it for the body,
                // and a body that needs no escaping is the common case. Read as markup: the sanitizer drops whatever
                // is not whitelisted, whereas escaping would show a real HTML body to the reader as source code.
                html = string(item.path("content"));
            }
            if (html == null) {
                return null;
            }
        }
        return HtmlSanitizer.clean(html, base);
    }

    /**
     * 03 §5.3 rule 3: {@code id} first, then {@code url}, then the same content hash the XML readers fall back to. A
     * numeric id stays a numeric id — {@code 123} must not reach the database as {@code "123.0"}.
     */
    private static EntryFields.Id id(JsonNode item, String url, String title, Instant published, String feedUrl) {
        String id = string(item.path("id"));
        if (id != null) {
            return new EntryFields.Id(id, GuidSource.JSON_ID);
        }
        if (url != null) {
            return new EntryFields.Id(url, GuidSource.LINK);
        }
        return new EntryFields.Id(GuidSource.FALLBACK_HASH.stored() + ":" + Hashing
            .sha256Hex(feedUrl + "|" + (title == null ? "" : title) + "|" + published), GuidSource.FALLBACK_HASH);
    }

    private static List<NormalizedEntry.Author> authors(JsonNode item) {
        List<NormalizedEntry.Author> found = new ArrayList<>();
        // 1.0 calls this "author" and allows a bare string; 1.1 calls it "authors" and allows one object too.
        nodes(item.path("authors")).forEach(node -> found.add(author(node)));
        if (found.isEmpty()) {
            found.add(author(item.path("author")));
        }
        nodes(item.path("contributors")).forEach(node -> found.add(author(node)));
        found.removeIf(author -> author.name() == null && author.email() == null && author.uri() == null);
        return found;
    }

    private static NormalizedEntry.Author author(JsonNode node) {
        if (node.isString()) {
            return new NormalizedEntry.Author(string(node), null, null);
        }
        return new NormalizedEntry.Author(string(node.path("name")), string(node.path("email")),
            Addresses.absolute(null, string(node.path("url"))));
    }

    private static List<String> tags(JsonNode item) {
        List<String> found = new ArrayList<>();
        for (JsonNode node : nodes(item.path("tags"))) {
            String value = string(node);
            if (value != null) {
                found.add(value);
            }
        }
        return found;
    }

    private static List<NormalizedEntry.Enclosure> attachments(JsonNode item, String feedUrl) {
        List<NormalizedEntry.Enclosure> found = new ArrayList<>();
        for (JsonNode node : nodes(item.path("attachments"))) {
            String url = Addresses.absolute(feedUrl, string(node.path("url")));
            if (url == null) {
                continue;
            }
            found.add(new NormalizedEntry.Enclosure(url, string(node.path("mime_type")),
                asLong(node.path("size_in_bytes")), asLong(node.path("duration_in_seconds"))));
        }
        return found;
    }

    /**
     * 03 §5.2 line 541 keeps unknown fields as opaque text and never lets them drive logic. A JSON Feed document is
     * open by design, so only the top-level scalar keys nobody mapped are worth showing the operator; nested
     * objects and arrays stay out, because a stray object tells the health page nothing.
     */
    private static Map<String, String> extras(JsonNode item) {
        Map<String, String> found = new LinkedHashMap<>();
        for (String key : item.propertyNames()) {
            if (found.size() >= 32 || KNOWN.contains(key)) {
                continue;
            }
            JsonNode value = item.path(key);
            if (!value.isString() && !value.isNumber()) {
                continue;
            }
            String text = string(value);
            if (text != null) {
                found.put(key, text.length() > 300 ? text.substring(0, 300) : text);
            }
        }
        return found;
    }

    private static JsonNode parse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isObject()) {
                throw new FeedParseException("JSON feed root is not an object");
            }
            return root;
        } catch (JacksonException e) {
            // The message would quote the document, and the document can be 10MB of somebody's blog.
            throw new FeedParseException("malformed JSON: " + e.getClass().getSimpleName());
        }
    }

    /** An array's elements, a single object as a one-element list, or nothing at all. */
    private static List<JsonNode> nodes(JsonNode node) {
        List<JsonNode> found = new ArrayList<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return found;
        }
        if (node.isArray()) {
            node.forEach(found::add);
            return found;
        }
        found.add(node);
        return found;
    }

    /** Text of a node: strings as written, numbers via {@code asString} so an id keeps its digits. Else null. */
    private static String string(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isString()) {
            return Tags.blankToNull(node.stringValue().trim());
        }
        return node.isNumber() ? node.asString() : null;
    }

    /** A size or duration that is not a number is absent; {@code null} reaches the column, not a guessed zero. */
    private static Long asLong(JsonNode node) {
        if (!node.isNumber()) {
            return null;
        }
        OptionalLong value = node.longValueOpt();
        return value.isPresent() ? value.getAsLong() : null;
    }
}
