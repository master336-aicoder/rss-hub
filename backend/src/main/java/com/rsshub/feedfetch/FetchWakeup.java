package com.rsshub.feedfetch;

/**
 * Asks the fetch scheduler to look for due work now instead of whenever its next round happens to run.
 *
 * <p>The round is 15 seconds wide, so {@code POST /api/feeds/{id}/refresh} on its own means "it will start within
 * a quarter of a minute" — true, and indistinguishable from a broken button. This is the one line the API needs to
 * turn that into "it starts now", without the request thread ever touching the network.
 */
@FunctionalInterface
public interface FetchWakeup {

    /** Nothing to wake: the scheduler is not running in this process. */
    FetchWakeup DISABLED = () -> {
    };

    /**
     * Hands the drain to the scheduler's own thread. Returns before any fetch finishes — the queue, not this call,
     * is what the operator's response describes. Implementations must not block the caller and must swallow their
     * own failures, because a wakeup is a convenience layered on a round that would have run anyway.
     */
    void wake();
}
