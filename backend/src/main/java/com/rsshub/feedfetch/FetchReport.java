package com.rsshub.feedfetch;

import java.time.Instant;

/**
 * One §4.3 decision, split into the two halves it writes: the health columns of {@code feed} and the next fire
 * time of {@code scheduled_task}. They go in one transaction because a feed that looks healthy with a task that
 * never re-arms (or the reverse) is the failure mode nobody notices until the inbox stops moving.
 */
public record FetchReport(Feed feed, Arm arm) {

    /**
     * @param state {@code active} / {@code error} / {@code gone} / {@code muted} / {@code paused}
     * @param canonicalUrl non-null only after a 301/308 moved the feed
     * @param elapsedMs duration of the exchange, {@code 0} when nothing was fetched
     * @param contentChanged whether to bump {@code last_change_at}
     */
    public record Feed(String state, Integer httpStatus, int failCount, int notfoundStreak, String error,
        String etag, String lastModified, String canonicalUrl, long elapsedMs, boolean contentChanged) {

        /** Counters are carried through untouched on the paths 03 §4.3 does not punish (304, 429, size cap). */
        public static Feed of(String state, Integer httpStatus, int failCount, int notfoundStreak, String error) {
            return new Feed(state, httpStatus, failCount, notfoundStreak, error, null, null, null, 0, false);
        }
    }

    /**
     * @param cancel true sends the task to {@code cancelled} instead of re-arming it, which is how a 410 stops
     *        costing requests without deleting the history
     * @param backoff true keeps {@code attempts} so the next failure backs off further; false is a clean round and
     *        resets it
     */
    public record Arm(Instant fireAt, boolean cancel, boolean backoff, String reason) {

        public static Arm until(Instant when) {
            return new Arm(when, false, false, null);
        }

        public static Arm backoffUntil(Instant when, String reason) {
            return new Arm(when, false, true, reason);
        }

        public static Arm cancelled(String reason) {
            return new Arm(null, true, false, reason);
        }
    }
}
