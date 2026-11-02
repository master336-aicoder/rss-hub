package com.rsshub.rules;

import com.rsshub.common.ApiException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import tools.jackson.databind.JsonNode;

/**
 * 03 §8.2's quiet hours: a rule that hits during them is recorded but never sent, and the record is what keeps the
 * inbox mark honest (FR-5.3).
 *
 * <p>The window may wrap past midnight — {@code 23:00→07:00} is the common case — and the time zone is required,
 * because "23:00" on a server set to UTC is not the hour the operator meant. A site that wants its own display zone
 * writes it into the rule.
 */
record QuietHours(LocalTime from, LocalTime to, ZoneId zone) {

    static QuietHours parse(String json) {
        JsonNode node = RuleJson.object(json, "quiet_hours");
        String from = RuleJson.text(node, "from");
        String to = RuleJson.text(node, "to");
        String tz = RuleJson.text(node, "tz");
        if (from == null || to == null || tz == null) {
            throw ApiException.badRequest("quiet_hours_incomplete",
                "quiet_hours needs from, to and tz, for example {\"from\":\"23:00\",\"to\":\"07:00\","
                    + "\"tz\":\"Asia/Shanghai\"}");
        }
        return new QuietHours(clock(from, "from"), clock(to, "to"), zone(tz));
    }

    /** Boundary-inclusive at {@code from}, exclusive at {@code to}: an hour of quiet is 60 minutes, not 61. */
    boolean quietAt(Instant instant) {
        LocalTime now = instant.atZone(zone).toLocalTime();
        if (from.equals(to)) {
            return false;
        }
        return from.isBefore(to)
            ? !now.isBefore(from) && now.isBefore(to)
            : !now.isBefore(from) || now.isBefore(to);
    }

    private static LocalTime clock(String value, String field) {
        try {
            return LocalTime.parse(value);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("invalid_" + field,
                field + " must be a 24-hour HH:mm, not [" + value + "]");
        }
    }

    private static ZoneId zone(String value) {
        try {
            return ZoneId.of(value);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("unknown_tz", "No such time zone: " + value);
        }
    }
}
