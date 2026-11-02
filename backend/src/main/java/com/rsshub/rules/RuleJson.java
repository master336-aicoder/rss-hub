package com.rsshub.rules;

import com.rsshub.common.ApiException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The four JSON columns {@code rule} owns are written by this application and edited by one operator, so a broken
 * document is a bad request rather than a stack trace — which is why every read goes through here and names the
 * field it failed on. The columns stay as text in the records; only the matcher and the API decode them.
 */
final class RuleJson {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private RuleJson() {
    }

    /** @param what the column's name in the operator's language, so the 400 can say which box was wrong */
    static JsonNode object(String json, String what) {
        if (json == null || json.isBlank()) {
            return MAPPER.createObjectNode();
        }
        JsonNode node = parse(json, what);
        if (!node.isObject()) {
            throw ApiException.badRequest("invalid_" + what, what + " must be a JSON object");
        }
        return node;
    }

    static JsonNode array(String json, String what) {
        JsonNode node = parse(json == null || json.isBlank() ? "[]" : json, what);
        if (!node.isArray()) {
            throw ApiException.badRequest("invalid_" + what, what + " must be a JSON array");
        }
        return node;
    }

    private static JsonNode parse(String json, String what) {
        try {
            return MAPPER.readTree(json);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("invalid_" + what,
                what + " is not valid JSON: " + shortMessage(e));
        }
    }

    /** The field is optional; an absent or empty array means "this condition is not used". */
    static List<String> strings(JsonNode node, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : elements(node, field)) {
            String text = item.isTextual() ? item.asString() : null;
            if (text != null && !text.isBlank()) {
                values.add(text.trim());
            }
        }
        return List.copyOf(values);
    }

    static List<Long> longs(JsonNode node, String field) {
        List<Long> values = new ArrayList<>();
        for (JsonNode item : elements(node, field)) {
            if (item.isNumber() || item.isString()) {
                try {
                    values.add(item.isNumber() ? item.asLong() : Long.parseLong(item.asString().trim()));
                } catch (RuntimeException e) {
                    throw ApiException.badRequest("invalid_match", field + " holds a value that is not an id");
                }
            }
        }
        return List.copyOf(values);
    }

    static boolean bool(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.path(field);
        return value.isBoolean() ? value.asBoolean() : fallback;
    }

    /** Clamped here rather than in each caller: a window of a million minutes is a typo, not a policy. */
    static int integer(JsonNode node, String field, int fallback, int min, int max) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) {
            return fallback;
        }
        int parsed = (int) value.asLong();
        if (parsed < min || parsed > max) {
            throw ApiException.badRequest("invalid_" + field,
                field + " must be between " + min + " and " + max + " (got " + parsed + ")");
        }
        return parsed;
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        String raw = value.isTextual() ? value.asString() : null;
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private static List<JsonNode> elements(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw ApiException.badRequest("invalid_match", field + " must be an array");
        }
        List<JsonNode> items = new ArrayList<>();
        value.valueStream().forEach(items::add);
        return items;
    }

    private static String shortMessage(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
