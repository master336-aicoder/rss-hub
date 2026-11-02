package com.rsshub.rules;

import com.rsshub.feedstore.EntryNewEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * The one copy of what a hit looks like on the wire (03 §8.3's fixed field list), rendered when the hit is recorded
 * and carried by the {@code deliver} task until it is sent.
 *
 * <h2>Why it is rendered at hit time</h2>
 * {@code notification_log} keeps no copy of the entry text or the channel target, and {@code rule} can be edited or
 * deleted minutes later. A delivery that re-reads those rows would send a different message than the one the
 * operator was shown, or nothing at all once the rule is gone. The task payload is the frozen fact — which is also
 * what makes a hand replay honest: it re-sends what was decided, not what is true now.
 */
final class AlertPayload {

    private static final ObjectMapper MAPPER = RuleJson.MAPPER;

    /** The summary a channel shows. Long enough for an RSS {@code description}, short enough for a chat bubble. */
    private static final int SUMMARY_MAX_CHARS = 700;

    private AlertPayload() {
    }

    static String render(long notificationId, RuleRepository.StoredRule rule, Action action, EntryNewEvent entry,
        List<String> keywords) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notificationId", notificationId);
        payload.put("channel", action.channel());
        payload.put("target", action.target());
        payload.put("secretRef", action.secretRef());
        payload.put("ruleId", rule.id());
        payload.put("ruleName", rule.name());
        payload.put("entryId", entry.entryId());
        payload.put("title", entry.title());
        payload.put("url", entry.url());
        payload.put("summary", cut(entry.summary(), SUMMARY_MAX_CHARS));
        payload.put("feedTitle", entry.feedTitle());
        payload.put("matchedKeywords", keywords);
        payload.put("publishedAt", text(entry.effectiveTime()));
        return MAPPER.writeValueAsString(payload);
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static String cut(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
