package com.rsshub.rules;

import com.rsshub.common.ApiException;
import com.rsshub.common.Hashing;
import com.rsshub.feedstore.EntryNewEvent;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/**
 * 03 §8.2's suppression window: how long one rule stays quiet about the same thing after it has already spoken.
 *
 * <p>The design's key is {@code rule:{ruleId}:{命中键}}, and the only real choice is what 命中键 means. Three
 * granularities are configurable, and each produces a bounded string — a raw title cannot be a key, because
 * {@code dedupe_key} is VARCHAR(512) and titles are not.
 */
record Suppression(String granularity, int hours) {

    static final String PER_FEED = "perFeed";
    static final String PER_CONTENT_HASH = "perContentHash";
    static final String PER_TITLE_NORM = "perTitleNorm";

    /** 03 §8.2's default; a month would make the rule look broken rather than quiet. */
    private static final int MAX_HOURS = 720;

    static Suppression parse(String json) {
        JsonNode node = RuleJson.object(json, "suppression");
        String key = RuleJson.text(node, "key");
        if (key == null) {
            key = PER_FEED;
        }
        if (!key.equals(PER_FEED) && !key.equals(PER_CONTENT_HASH) && !key.equals(PER_TITLE_NORM)) {
            throw ApiException.badRequest("unknown_suppression_key",
                "suppression.key must be perFeed, perContentHash or perTitleNorm");
        }
        return new Suppression(key, RuleJson.integer(node, "hours", 6, 0, MAX_HOURS));
    }

    /** {@code hours = 0} means "do not suppress": the operator asked for every hit, and got it. */
    boolean enabled() {
        return hours > 0;
    }

    /** The full {@code notification_log.dedupe_key} for one hit. */
    String dedupeKey(long ruleId, EntryNewEvent entry) {
        return "rule:" + ruleId + ":" + granularity + ":" + hitKey(entry);
    }

    private String hitKey(EntryNewEvent entry) {
        return switch (granularity) {
            // A feed id, not a URL: the source's address can move (03 §4.3 rewrites canonical_url) while the
            // subscription, and therefore the "same source" in FR-5.3, stays the same row.
            case PER_FEED -> "feed:" + entry.feedId();
            case PER_CONTENT_HASH -> "hash:" + (entry.contentHash() == null ? "none" : entry.contentHash());
            case PER_TITLE_NORM -> "title:" + Hashing.sha256Hex(titleNorm(entry.title()));
            default -> throw new IllegalStateException("unreachable: parse() validated the granularity");
        };
    }

    /**
     * Lower-cased, whitespace-collapsed, cut at 200 characters — the same normalisation the preview shows, so what
     * the operator reads and what dedupes are built the same way. Hashed rather than stored so a long CJK title
     * cannot overflow the key column.
     */
    static String titleNorm(String title) {
        String collapsed = title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 200 ? collapsed : collapsed.substring(0, 200);
    }
}
