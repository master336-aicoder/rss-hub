package com.rsshub.rules;

import com.rsshub.common.ApiException;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * One {@code rule.match} column, decoded (03 §8.1, FR-5.1).
 *
 * <p>Every field is optional and an absent one means "this condition is not used" — the empty spec is <em>not</em>
 * "match everything", because a rule that alerts on the whole inbox is a rule the operator turns off within a week.
 * {@link #validate()} refuses it for that reason.
 *
 * @param wholeWord applies to the keyword lists, not to {@code regex}: a regex author already has {@code \b}
 * @param caseSensitive defaults to false, which is what "找 OOM" means in practice
 * @param windowMinutes 0 disables the window; otherwise only entries whose {@code published_at} (or
 *     {@code fetched_at} when the source sent no date) falls inside it can hit
 */
record MatchSpec(List<String> includeAny, List<String> includeAll, List<String> excludeAny, List<String> regex,
    boolean wholeWord, boolean caseSensitive, List<Long> feedIds, List<String> groups, List<String> tags,
    int windowMinutes) {

    /** 03 §8.1's ceiling on a window: a month, past which the rule is really a backfill. */
    private static final int MAX_WINDOW_MINUTES = 43200;

    static MatchSpec parse(String json) {
        JsonNode node = RuleJson.object(json, "match");
        return new MatchSpec(RuleJson.strings(node, "includeAny"), RuleJson.strings(node, "includeAll"),
            RuleJson.strings(node, "excludeAny"), RuleJson.strings(node, "regex"),
            RuleJson.bool(node, "wholeWord", false), RuleJson.bool(node, "caseSensitive", false),
            RuleJson.longs(node, "feedIds"), RuleJson.strings(node, "groups"), RuleJson.strings(node, "tags"),
            RuleJson.integer(node, "windowMinutes", 0, 0, MAX_WINDOW_MINUTES));
    }

    /**
     * Fails the save rather than the alert, because a rule that fires on everything is only noticed once the
     * channel has been hammered.
     */
    void validate() {
        // windowMinutes belongs in this test: a rule that only sets a window is not "empty", and the message it
        // gets should be the one that explains that a window narrows rather than starts a rule.
        if (includeAny.isEmpty() && includeAll.isEmpty() && excludeAny.isEmpty() && regex.isEmpty()
            && feedIds.isEmpty() && groups.isEmpty() && tags.isEmpty() && windowMinutes == 0) {
            throw ApiException.badRequest("match_too_broad",
                "A rule needs at least one condition; matching every entry in the inbox is not what a rule is for");
        }
        if (onlyHasNegatives()) {
            throw ApiException.badRequest("match_too_broad",
                "Exclude and window conditions narrow a rule down, they cannot start one: add an include, a regex, "
                    + "a feed, a group or a tag");
        }
        for (List<String> terms : List.of(includeAny, includeAll, excludeAny)) {
            for (String term : terms) {
                if (term.length() > 200) {
                    throw ApiException.badRequest("keyword_too_long", "A keyword is capped at 200 characters");
                }
            }
        }
        for (String pattern : regex) {
            if (pattern.length() > 200) {
                throw ApiException.badRequest("regex_too_long",
                    "A pattern above 200 characters is refused (03 §8.1): java.util.regex has no timeout");
            }
            RuleMatcher.checkPattern(pattern);
        }
    }

    /** An {@code excludeAny} alone matches every entry that does not contain the word — the opposite of an alert. */
    private boolean onlyHasNegatives() {
        boolean positives = !includeAny.isEmpty() || !includeAll.isEmpty() || !regex.isEmpty()
            || !feedIds.isEmpty() || !groups.isEmpty() || !tags.isEmpty();
        return !positives;
    }
}
