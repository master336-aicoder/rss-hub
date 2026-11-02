package com.rsshub.feedfetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 03 §4.1 as arithmetic. These are the two places where a wrong number is invisible until it hurts: a feed that
 * advertises ttl=1 must not become a hot loop, and a feed that has failed 60 times must not ask for a backoff
 * longer than the ceiling — or overflow the shift that computes it.
 */
class FetchPolicyTest {

    @Test
    void unsetConfigurationStillProducesTheDesignedPolicy() {
        FetchPolicy policy = new FetchPolicy(null, null, null, null, null, 0, -1, null, null, null, 0, null, null,
            null);

        assertThat(policy.interval()).isEqualTo(Duration.ofMinutes(60));
        assertThat(policy.minInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.jitter()).isEqualTo(Duration.ofSeconds(300));
        assertThat(policy.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.readTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.maxBytes()).isEqualTo(10 * 1024 * 1024);
        assertThat(policy.maxRedirects()).isEqualTo(3);
        assertThat(policy.perHostGap()).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.userAgent()).contains("rss-hub");
        assertThat(policy.authBackoff()).isEqualTo(Duration.ofHours(24));
        assertThat(policy.concurrency()).isEqualTo(6);
        assertThat(policy.backoffBase()).isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.backoffCap()).isEqualTo(Duration.ofHours(24));
        assertThat(policy.notfoundGoneDays()).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void theAdvertisedTtlWinsInsideTheFloor() {
        FetchPolicy policy = policy();

        assertThat(policy.intervalFor(120)).isEqualTo(Duration.ofMinutes(120));
        // A ttl shorter than the global interval is a request to poll faster, and 03 §4.1 says yes within the floor.
        assertThat(policy.intervalFor(10)).isBetween(Duration.ofMinutes(5), Duration.ofMinutes(60));
        assertThat(policy.intervalFor(6)).isEqualTo(Duration.ofMinutes(6));
        assertThat(policy.intervalFor(1)).isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.intervalFor(0)).isEqualTo(Duration.ofMinutes(60));
        assertThat(policy.intervalFor(-5)).isEqualTo(Duration.ofMinutes(60));
        assertThat(policy.intervalFor(null)).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    void backoffDoublesFromTheBaseAndStopsAtTheCap() {
        FetchPolicy policy = policy();

        assertThat(policy.backoffFor(1)).isEqualTo(Duration.ofMinutes(10));
        assertThat(policy.backoffFor(2)).isEqualTo(Duration.ofMinutes(20));
        assertThat(policy.backoffFor(3)).isEqualTo(Duration.ofMinutes(40));
        assertThat(policy.backoffFor(6)).isEqualTo(Duration.ofMinutes(320));
        assertThat(policy.backoffFor(20)).isEqualTo(Duration.ofHours(24));
    }

    /**
     * The first failing round arrives with fail_count already at 1 in some paths and 0 in others; both must produce
     * a delay, never zero — a zero backoff would spin the dispatcher.
     */
    @Test
    void backoffNeverReturnsZeroForUnknownCounts() {
        FetchPolicy policy = policy();

        assertThat(policy.backoffFor(0)).isEqualTo(Duration.ofMinutes(10));
        assertThat(policy.backoffFor(-7)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void anAbsurdFailCountCannotOverflowTheShiftIntoANegativeDelay() {
        FetchPolicy policy = policy();

        assertThat(policy.backoffFor(Integer.MAX_VALUE)).isEqualTo(Duration.ofHours(24));
        assertThat(policy.backoffFor(120)).isEqualTo(Duration.ofHours(24));
    }

    private static FetchPolicy policy() {
        return new FetchPolicy(Duration.ofMinutes(60), Duration.ofMinutes(5), Duration.ZERO,
            Duration.ofSeconds(10), Duration.ofSeconds(30), 4096, 3, Duration.ofSeconds(30), "rss-hub-test",
            Duration.ofHours(24), 6, Duration.ofMinutes(5), Duration.ofHours(24), Duration.ofDays(30));
    }
}
