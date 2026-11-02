package com.rsshub.rules;

import com.rsshub.common.ApiException;
import com.rsshub.feedstore.EntryNewEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides whether one entry hits one rule (03 §8.1, FR-5.1). Pure: no database, no clock beyond the caller's
 * {@code now}, and above all no external call — 03 §8.1 keeps rule evaluation off the network so a fetch round's
 * latency stays predictable and an alert costs nothing to decide.
 *
 * <h2>How the conditions combine</h2>
 * Every condition the rule actually uses must hold; the lists inside one condition are alternatives. So
 * {@code includeAny:["OOM","panic"]} plus {@code groups:["生产"]} plus {@code excludeAny:["演练"]} reads as
 * "in 生产, says OOM or panic, and is not a drill". A rule with no content condition at all — only a feed, group,
 * tag or window — hits on anything that passes its scope, which is how "tell me when this source posts" is written.
 *
 * <h2>What the keywords read</h2>
 * Title plus summary, truncated to {@link #HAYSTACK_MAX_CHARS}. The body is deliberately out: scanning it is the
 * expensive half of a fetch round, the summary is what a publisher writes for exactly this, and an operator who
 * really wants full-text has M6's search.
 *
 * <h2>Why the regex guards are a blacklist</h2>
 * {@code java.util.regex} has no match timeout — 03 §8.1 says so and §14 lists RE2 as the actual fix. The patterns
 * here come from this operator's own keyboard, so the defence is bounded input plus a shape blacklist that refuses
 * the nested quantifiers which are the practical way to write a quadratic bomb, plus a catch on the error a runaway
 * backtracking raises. That is mitigation, not proof, and it is written down as such.
 */
@Component
final class RuleMatcher {

    private static final Logger log = LoggerFactory.getLogger(RuleMatcher.class);

    /** The truncation that bounds every match: 4k characters is a long article's worth of haystack. */
    static final int HAYSTACK_MAX_CHARS = 4000;

    /** Compiled patterns held per process. Bounded because an operator can rewrite a rule indefinitely. */
    private static final int CACHE_MAX = 256;

    /** Compiled whole-word term lookups, keyed by the term plus its case flag. */
    private static final Map<String, Pattern> WORD_CACHE = new ConcurrentHashMap<>();

    /** A quantifier applied to something that can already repeat: {@code (a+)+}, {@code (x*)*}, {@code (\d{3})+}. */
    private static final Pattern NESTED_QUANTIFIER = Pattern.compile("\\)\\??[*+]|\\}\\??[*+]");

    /** {@code ^} and {@code $} per line, because the haystack is title, newline, summary. */
    private static final int FLAGS = Pattern.MULTILINE;

    /** The answer, including the words that actually hit: FR-5.4's preview and the webhook payload both show them. */
    record Outcome(boolean matched, List<String> keywords) {

        private static final Outcome NO = new Outcome(false, List.of());

        static Outcome no() {
            return NO;
        }

        static Outcome yes(List<String> keywords) {
            return new Outcome(true, List.copyOf(keywords));
        }
    }

    private final Map<String, Pattern> cache = new ConcurrentHashMap<>();

    Outcome match(MatchSpec spec, EntryNewEvent entry, Instant now) {
        if (!spec.feedIds().isEmpty() && !spec.feedIds().contains(entry.feedId())) {
            return Outcome.no();
        }
        if (!spec.groups().isEmpty() && !containsIgnoreCase(spec.groups(), entry.groupName())) {
            return Outcome.no();
        }
        if (!spec.tags().isEmpty() && !intersectsIgnoreCase(spec.tags(), entry.tags())) {
            return Outcome.no();
        }
        if (spec.windowMinutes() > 0) {
            Instant when = entry.effectiveTime();
            if (when == null || when.isBefore(now.minus(Duration.ofMinutes(spec.windowMinutes())))) {
                return Outcome.no();
            }
        }

        String haystack = haystack(entry);
        if (!spec.excludeAny().isEmpty() && firstFound(spec.excludeAny(), haystack, spec) != null) {
            return Outcome.no();
        }

        List<String> keywords = new ArrayList<>();
        boolean contentRule = !spec.includeAny().isEmpty() || !spec.includeAll().isEmpty() || !spec.regex().isEmpty();

        if (!spec.includeAny().isEmpty()) {
            List<String> found = allFound(spec.includeAny(), haystack, spec);
            if (found.isEmpty()) {
                return Outcome.no();
            }
            keywords.addAll(found);
        }
        if (!spec.includeAll().isEmpty()) {
            List<String> found = allFound(spec.includeAll(), haystack, spec);
            if (found.size() != spec.includeAll().size()) {
                return Outcome.no();
            }
            keywords.addAll(found);
        }
        if (!spec.regex().isEmpty()) {
            List<String> found = foundPatterns(spec.regex(), haystack);
            if (found.isEmpty()) {
                return Outcome.no();
            }
            keywords.addAll(found);
        }

        if (!contentRule && !hasScopeCondition(spec)) {
            // MatchSpec.validate() refuses this on save; kept as a guard so a hand-edited row cannot alert forever.
            return Outcome.no();
        }
        Set<String> distinct = new LinkedHashSet<>(keywords);
        return Outcome.yes(List.copyOf(distinct));
    }

    private static boolean hasScopeCondition(MatchSpec spec) {
        return !spec.feedIds().isEmpty() || !spec.groups().isEmpty() || !spec.tags().isEmpty()
            || spec.windowMinutes() > 0;
    }

    private static String haystack(EntryNewEvent entry) {
        String title = entry.title() == null ? "" : entry.title();
        String summary = entry.summary() == null ? "" : entry.summary();
        String joined = title + "\n" + summary;
        return joined.length() <= HAYSTACK_MAX_CHARS ? joined : joined.substring(0, HAYSTACK_MAX_CHARS);
    }

    private String firstFound(List<String> terms, String haystack, MatchSpec spec) {
        for (String term : terms) {
            if (contains(haystack, term, spec)) {
                return term;
            }
        }
        return null;
    }

    private List<String> allFound(List<String> terms, String haystack, MatchSpec spec) {
        List<String> found = new ArrayList<>();
        for (String term : terms) {
            if (contains(haystack, term, spec)) {
                found.add(term);
            }
        }
        return found;
    }

    private List<String> foundPatterns(List<String> patterns, String haystack) {
        List<String> found = new ArrayList<>();
        for (String source : patterns) {
            Pattern pattern = pattern(source);
            if (pattern == null) {
                continue;
            }
            try {
                if (pattern.matcher(haystack).find()) {
                    found.add(source);
                }
            } catch (RuntimeException | StackOverflowError e) {
                // StackOverflowError is what a runaway backtrack raises. Dropping this one entry's regex beats
                // losing the fetch round with it: the rule stays enabled, and the log names the pattern.
                log.warn("regex [{}] threw while matching: {}", source, e.toString());
            }
        }
        return found;
    }

    /** A pattern that does not compile is skipped, not fatal: it was valid when the rule was saved and the row
     *  may have been edited by hand. The save path refuses it, so this only costs an alert. */
    private Pattern pattern(String source) {
        Pattern cached = cache.get(source);
        if (cached != null) {
            return cached;
        }
        try {
            Pattern compiled = Pattern.compile(source, FLAGS);
            if (cache.size() < CACHE_MAX) {
                cache.put(source, compiled);
            }
            return compiled;
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    /**
     * A whole-word hit quotes the term into a regex so none of it can become an operator, then demands that
     * neither neighbour is a letter or a digit — which is also what makes it work for a Chinese term, where
     * {@code \b} would never fire.
     */
    private static boolean contains(String haystack, String term, MatchSpec spec) {
        if (!spec.wholeWord()) {
            return spec.caseSensitive()
                ? haystack.contains(term)
                : haystack.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT));
        }
        String key = term + (spec.caseSensitive() ? "\u0000s" : "\u0000i");
        Pattern pattern = WORD_CACHE.get(key);
        if (pattern == null) {
            pattern = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(term) + "(?![\\p{L}\\p{N}])",
                spec.caseSensitive() ? FLAGS : FLAGS | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            if (WORD_CACHE.size() < CACHE_MAX) {
                WORD_CACHE.put(key, pattern);
            }
        }
        return pattern.matcher(haystack).find();
    }

    private static boolean containsIgnoreCase(List<String> candidates, String value) {
        return value != null && candidates.stream().anyMatch(candidate -> candidate.equalsIgnoreCase(value.trim()));
    }

    private static boolean intersectsIgnoreCase(List<String> wanted, List<String> present) {
        if (present == null || present.isEmpty()) {
            return false;
        }
        return present.stream()
            .filter(Objects::nonNull)
            .anyMatch(tag -> wanted.stream().anyMatch(wantedTag -> wantedTag.equalsIgnoreCase(tag.trim())));
    }

    /** Used by {@link MatchSpec#validate()} on save, so a typo reaches the operator as a 400. */
    static void checkPattern(String pattern) {
        try {
            Pattern.compile(pattern, FLAGS);
        } catch (PatternSyntaxException e) {
            throw ApiException.badRequest("invalid_regex", "The pattern does not compile: " + e.getDescription());
        }
        if (NESTED_QUANTIFIER.matcher(pattern).find()) {
            throw ApiException.badRequest("unsafe_regex",
                "A quantified group such as (a+)+ can hang a match and java.util.regex has no timeout: rewrite it"
                    + " without nesting the quantifier");
        }
    }
}
