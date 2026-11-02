package com.rsshub.events;

import com.rsshub.common.Hashing;
import java.util.List;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The two JSON columns this table pair owns ({@code event_item.tags}) plus the ETag id digest.
 *
 * <p>Digesting here rather than in SQL is the same reason {@code editorial} does it: {@code LISTAGG} and
 * {@code string_agg} are different functions in the two dialects, and an ETag seed must not be one of the things
 * that diverge between dev and production.
 */
final class EventJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EventJson() {
    }

    static List<String> stringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> values = new java.util.ArrayList<>();
            node.forEach(item -> {
                String text = item.isTextual() ? item.asString() : null;
                if (text != null && !text.isBlank()) {
                    values.add(text.trim());
                }
            });
            return List.copyOf(values);
        } catch (RuntimeException e) {
            // A tag column we wrote ourselves cannot legally be broken; losing the tags beats losing the event.
            return List.of();
        }
    }

    static String listJson(List<String> values) {
        var array = MAPPER.createArrayNode();
        if (values != null) {
            values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .forEach(array::add);
        }
        return array.toString();
    }

    /** Order-sensitive on purpose: the outlet's newest-first id list is what a retraction changes. */
    static String idDigest(List<Long> ids) {
        return Hashing.sha256Hex(ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
    }
}
