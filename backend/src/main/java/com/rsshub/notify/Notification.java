package com.rsshub.notify;

import com.rsshub.common.ApiException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One alert, as the {@code deliver} task's payload recorded it at hit time (03 §8.1's "同事务只写命中事实").
 *
 * <p>The fields are 03 §8.3's fixed webhook list plus three the queue needs to know where to put them: the
 * notification's own id, the channel and its target. {@code secret} is not in the payload — a signing key is
 * resolved from the environment when the attempt runs, so it never reaches the database (03 §9.1).
 */
public record Notification(long notificationId, String channel, String target, String secretRef, String secret,
    long ruleId, String ruleName, long entryId, String title, String url, String summary, String feedTitle,
    List<String> matchedKeywords, Instant publishedAt) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    Notification withSecret(String resolved) {
        return new Notification(notificationId, channel, target, secretRef, resolved, ruleId, ruleName, entryId,
            title, url, summary, feedTitle, matchedKeywords, publishedAt);
    }

    /** The generic webhook body: exactly the fields 03 §8.3 fixes, in that order. */
    public String webhookBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ruleId", ruleId);
        body.put("ruleName", ruleName);
        body.put("entryId", entryId);
        body.put("title", title);
        body.put("url", url);
        body.put("summary", summary);
        body.put("feedTitle", feedTitle);
        body.put("matchedKeywords", matchedKeywords);
        body.put("publishedAt", publishedAt == null ? null : publishedAt.toString());
        return MAPPER.writeValueAsString(body);
    }

    static Notification parse(String payload) {
        JsonNode node;
        try {
            node = MAPPER.readTree(payload);
        } catch (RuntimeException e) {
            throw ApiException.internal("payload_unreadable", "A deliver task's payload is not JSON: " + e);
        }
        return new Notification(node.path("notificationId").asLong(), text(node, "channel"), text(node, "target"),
            text(node, "secretRef"), null, node.path("ruleId").asLong(), text(node, "ruleName"),
            node.path("entryId").asLong(), text(node, "title"), text(node, "url"), text(node, "summary"),
            text(node, "feedTitle"), keywords(node), instant(node));
    }

    private static List<String> keywords(JsonNode node) {
        JsonNode array = node.path("matchedKeywords");
        if (!array.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        array.valueStream().forEach(item -> {
            if (item.isTextual()) {
                values.add(item.asString());
            }
        });
        return List.copyOf(values);
    }

    private static Instant instant(JsonNode node) {
        String raw = text(node, "publishedAt");
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            // A payload we wrote ourselves cannot hold a bad stamp; losing the date beats losing the alert.
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asString() : null;
    }
}
