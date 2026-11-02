package com.rsshub.feedfetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 03 §4.1: one request per host at a time, at least {@code per-host-gap} apart. The behaviour that matters is what
 * happens on the second call: the task is deferred, never marked failed, because a busy host is not a broken feed.
 */
class HostPolitenessTest {

    private static final Instant T0 = Instant.parse("2026-10-18T00:00:00Z");

    @Test
    void theFirstFetchOfAHostAlwaysProceeds() {
        HostPoliteness politeness = politeness(Duration.ofSeconds(30));

        assertThat(politeness.reserve("example.com", T0)).isEmpty();
    }

    @Test
    void aSecondFetchInsideTheGapIsDeferredToTheEndOfIt() {
        HostPoliteness politeness = politeness(Duration.ofSeconds(30));
        politeness.reserve("example.com", T0);
        politeness.release("example.com");

        Optional<Instant> deferred = politeness.reserve("example.com", T0.plusSeconds(5));

        assertThat(deferred).contains(T0.plusSeconds(30));
    }

    /** A reserve that defers must not start the clock again, otherwise the gap would never elapse. */
    @Test
    void aDeferredAttemptLeavesTheSlotAlone() {
        HostPoliteness politeness = politeness(Duration.ofSeconds(30));
        politeness.reserve("example.com", T0);
        politeness.release("example.com");
        politeness.reserve("example.com", T0.plusSeconds(5));

        assertThat(politeness.reserve("example.com", T0.plusSeconds(30))).isEmpty();
    }

    @Test
    void oneHostIsBlockedUntilItsFetchIsReleased() {
        HostPoliteness politeness = politeness(Duration.ofSeconds(30));
        politeness.reserve("example.com", T0);

        // Gap is satisfied but the previous fetch still owns the host: retry shortly instead of overlapping.
        assertThat(politeness.reserve("example.com", T0.plusSeconds(31))).contains(T0.plusSeconds(33));

        politeness.release("example.com");
        assertThat(politeness.reserve("example.com", T0.plusSeconds(40))).isEmpty();
    }

    @Test
    void releasingAnUnknownHostIsHarmless() {
        HostPoliteness politeness = politeness(Duration.ofSeconds(30));

        politeness.release("never-seen.example");

        assertThat(politeness.reserve("never-seen.example", T0)).isEmpty();
    }

    @Test
    void hostsDoNotShareTheirTimers() {
        HostPoliteness politeness = politeness(Duration.ofSeconds(30));
        politeness.reserve("a.example", T0);

        assertThat(politeness.reserve("b.example", T0)).isEmpty();
    }

    @Test
    void aZeroGapOnlySerialisesOverlappingFetches() {
        HostPoliteness politeness = politeness(Duration.ZERO);
        politeness.reserve("example.com", T0);
        politeness.release("example.com");

        assertThat(politeness.reserve("example.com", T0)).isEmpty();
    }

    private static HostPoliteness politeness(Duration gap) {
        FetchPolicy policy = new FetchPolicy(null, null, null, null, null, 0, -1, gap, null, null, 0, null, null,
            null);
        return new HostPoliteness(policy);
    }
}
