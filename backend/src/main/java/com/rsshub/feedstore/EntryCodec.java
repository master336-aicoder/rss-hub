package com.rsshub.feedstore;

import com.rsshub.feedparse.GuidSource;
import com.rsshub.feedparse.NormalizedEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code entry} table's JSON columns, written the way 03 §3 defines them: the database stores text and the
 * application owns the shape, so the same row means the same thing under H2 and Postgres.
 *
 * <p>Keys are written out here rather than derived from the records' field names, because a rename of
 * {@code NormalizedEntry.Enclosure} would otherwise silently rewrite the meaning of every stored row.
 */
final class EntryCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EntryCodec() {
    }

    static String authors(List<NormalizedEntry.Author> authors) {
        ArrayNode array = MAPPER.createArrayNode();
        for (NormalizedEntry.Author author : authors) {
            ObjectNode node = array.addObject();
            put(node, "name", author.name());
            put(node, "email", author.email());
            put(node, "uri", author.uri());
        }
        return array.toString();
    }

    static String categories(List<String> categories) {
        ArrayNode array = MAPPER.createArrayNode();
        categories.forEach(array::add);
        return array.toString();
    }

    /**
     * One element of {@link #categories(List)} exactly as it is written into the column, quotes included — which is
     * what lets a filter on "a" miss a row whose only category is "ab". Built with the same encoder that stored the
     * row, so the escaping of the value (quotes, backslashes) cannot drift from it.
     */
    static String categoryToken(String category) {
        String array = categories(List.of(category));
        return array.substring(1, array.length() - 1);
    }

    static String enclosures(List<NormalizedEntry.Enclosure> enclosures) {
        ArrayNode array = MAPPER.createArrayNode();
        for (NormalizedEntry.Enclosure enclosure : enclosures) {
            ObjectNode node = array.addObject();
            put(node, "url", enclosure.url());
            put(node, "mime_type", enclosure.mimeType());
            if (enclosure.sizeBytes() != null) {
                node.put("size_in_bytes", enclosure.sizeBytes());
            }
            if (enclosure.durationSeconds() != null) {
                node.put("duration_in_seconds", enclosure.durationSeconds());
            }
        }
        return array.toString();
    }

    /**
     * {@code entry.meta} holds the parse provenance the core columns cannot carry: which rung of 03 §5.3's id ladder
     * produced the guid (that is what the health page's "unstable id" column reads) and the namespaced extras.
     */
    static String meta(GuidSource guidSource, boolean stableId, Map<String, String> extras) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("guid_source", guidSource.stored());
        node.put("stable_id", stableId);
        if (!extras.isEmpty()) {
            ObjectNode extensions = node.putObject("extras");
            extras.forEach(extensions::put);
        }
        return node.toString();
    }

    private static void put(ObjectNode node, String key, String value) {
        if (value != null) {
            node.put(key, value);
        }
    }

    /**
     * Read side of the same shape. A row whose JSON is damaged (hand-edited database, an older writer) degrades to
     * an empty list rather than throwing: the inbox must still show the entry, and a missing author is not a reason
     * to lose an article the operator has not read yet.
     */
    static List<NormalizedEntry.Author> readAuthors(String json) {
        List<NormalizedEntry.Author> authors = new ArrayList<>();
        for (JsonNode node : array(json)) {
            authors.add(new NormalizedEntry.Author(text(node, "name"), text(node, "email"), text(node, "uri")));
        }
        return authors;
    }

    static List<String> readCategories(String json) {
        List<String> categories = new ArrayList<>();
        for (JsonNode node : array(json)) {
            String value = text(node, null);
            if (value != null && !value.isBlank()) {
                categories.add(value);
            }
        }
        return categories;
    }

    static List<NormalizedEntry.Enclosure> readEnclosures(String json) {
        List<NormalizedEntry.Enclosure> enclosures = new ArrayList<>();
        for (JsonNode node : array(json)) {
            enclosures.add(new NormalizedEntry.Enclosure(text(node, "url"), text(node, "mime_type"),
                number(node, "size_in_bytes"), number(node, "duration_in_seconds")));
        }
        return enclosures;
    }

    /** Which rung of 03 §5.3's id ladder produced this guid — the health view's "unstable id" signal. */
    static String readGuidSource(String metaJson) {
        if (metaJson == null || metaJson.isBlank()) {
            return null;
        }
        try {
            return text(MAPPER.readTree(metaJson).path("guid_source"), null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<JsonNode> array(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (RuntimeException e) {
            return List.of();
        }
        if (!root.isArray()) {
            return List.of();
        }
        List<JsonNode> items = new ArrayList<>();
        root.forEach(items::add);
        return items;
    }

    /** {@code field == null} reads the node itself, which is what a plain string array needs. */
    private static String text(JsonNode node, String field) {
        JsonNode value = field == null ? node : node.path(field);
        return value.isString() || value.isNumber() ? value.asString() : null;
    }

    private static Long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.longValue() : null;
    }
}
