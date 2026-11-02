package com.rsshub.repack;

import com.rsshub.common.Hashing;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Decoding the two JSON text columns a repacked item carries ({@code entry.authors}, {@code entry.categories}).
 *
 * <p>{@code feedstore.EntryFinder} already has readers for these columns and is not used: it returns
 * {@code feedparse} records, and pulling the ingestion layer's models into a public outlet is exactly what
 * 02 §3 rule 1 forbids. The stored shape is small and owned by one writer, so reading the two keys this outlet
 * displays is cheaper than crossing that boundary.
 */
final class RepackJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RepackJson() {
    }

    /** One display name per author, preferring the name and falling back to the mailbox, as the inbox does. */
    static List<String> authorNames(String json) {
        List<String> names = new ArrayList<>();
        for (JsonNode node : array(json)) {
            String name = text(node, "name");
            if (name == null || name.isBlank()) {
                name = text(node, "email");
            }
            if (name != null && !name.isBlank()) {
                names.add(name.trim());
            }
        }
        return List.copyOf(names);
    }

    static List<String> stringList(String json) {
        List<String> values = new ArrayList<>();
        for (JsonNode node : array(json)) {
            String value = node.isString() || node.isNumber() ? node.asString() : null;
            if (value != null && !value.isBlank()) {
                values.add(value.trim());
            }
        }
        return List.copyOf(values);
    }

    /** Order-sensitive on purpose: the outlet's newest-first id list is what a retraction changes. */
    static String idDigest(List<Long> ids) {
        return Hashing.sha256Hex(ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
    }

    private static List<JsonNode> array(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isArray()) {
                return List.of();
            }
            List<JsonNode> items = new ArrayList<>();
            root.forEach(items::add);
            return items;
        } catch (RuntimeException e) {
            // A column this application wrote cannot legally be broken; losing the tags beats losing the outlet.
            return List.of();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() || value.isNumber() ? value.asString() : null;
    }
}
