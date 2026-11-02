package com.rsshub.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 03 §9.1's "失败 5 次锁 15 分钟（内存计数器即可）": the counter lives in this process, so a restart unlocks —
 * which is acceptable here because the same restart that clears it also means the attacker lost their window, and
 * persisting failure counts would give anyone who can write the database a way to lock the operator out.
 *
 * <p>Counts per username, not per IP: behind no proxy of ours the real client address is not reliably knowable,
 * and a single-operator install has exactly one account worth attacking.
 */
public class LoginAttemptGuard {

    private static final int MAX_FAILURES = 5;
    private static final Duration LOCK = Duration.ofMinutes(15);

    private final Map<String, State> states = new ConcurrentHashMap<>();

    /** True while the account is refused regardless of the credential's correctness. */
    public boolean locked(String username) {
        State state = states.get(key(username));
        if (state == null) {
            return false;
        }
        if (state.lockUntil != null && Instant.now().isBefore(state.lockUntil)) {
            return true;
        }
        if (state.lockUntil != null) {
            states.remove(key(username));
        }
        return false;
    }

    /** When the lock expires; only meaningful right after {@link #locked} returned true. */
    public Instant lockExpiresAt(String username) {
        State state = states.get(key(username));
        return state == null || state.lockUntil == null ? Instant.now() : state.lockUntil;
    }

    public void recordFailure(String username) {
        states.compute(key(username), (ignored, existing) -> {
            State state = existing == null ? new State() : existing;
            if (state.lockUntil != null && Instant.now().isAfter(state.lockUntil)) {
                state.failures = 0;
                state.lockUntil = null;
            }
            state.failures++;
            if (state.failures >= MAX_FAILURES) {
                state.lockUntil = Instant.now().plus(LOCK);
            }
            return state;
        });
    }

    public void recordSuccess(String username) {
        states.remove(key(username));
    }

    /** Exposed for the test that would otherwise have to sleep for fifteen minutes. */
    static int maxFailures() {
        return MAX_FAILURES;
    }

    private static String key(String username) {
        return username == null ? "" : username.toLowerCase(java.util.Locale.ROOT);
    }

    private static final class State {
        private int failures;
        private Instant lockUntil;
    }
}
