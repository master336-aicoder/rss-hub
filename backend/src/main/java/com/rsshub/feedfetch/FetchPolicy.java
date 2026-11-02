package com.rsshub.feedfetch;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The knobs from doc/design/03-technical-design.md §4.1. Defaults are the values the design settled on, so an
 * unset {@code rsshub.fetch.*} still produces a polite fetcher. Per-feed cadence has two owners and this class is
 * where their precedence is decided: {@code feed.fetch_interval_minutes} (the operator) beats
 * {@code feed.ttl_minutes} (the publisher) beats {@code interval} (the install), and none of them goes below
 * {@code minInterval}.
 *
 * @param interval how often a healthy feed is re-fetched when it advertises no ttl
 * @param minInterval floor so a feed advertising ttl=1 cannot turn into a polling loop
 * @param jitter spread added to a next-fire time, keeps a batch from all arriving on the same second
 * @param connectTimeout time to establish the socket
 * @param readTimeout time for the whole response
 * @param maxBytes hard cap on bytes read; exceeding it aborts the body, it is not counted as a source failure
 * @param maxRedirects hops followed manually, each one re-validated by the SSRF guard
 * @param perHostGap minimum time between two requests to the same host
 * @param userAgent sent verbatim; contact information matters when a host starts blocking us
 * @param authBackoff how long to stay away after a 401/403, which no retry will fix
 * @param concurrency in-flight fetches across all feeds
 * @param backoffBase exponential backoff base for source failures
 * @param backoffCap ceiling on that backoff
 * @param notfoundGoneDays how long a feed may keep 404ing before we stop asking
 */
@ConfigurationProperties(prefix = "rsshub.fetch")
public record FetchPolicy(Duration interval, Duration minInterval, Duration jitter, Duration connectTimeout,
    Duration readTimeout, int maxBytes, int maxRedirects, Duration perHostGap, String userAgent,
    Duration authBackoff, int concurrency, Duration backoffBase, Duration backoffCap, Duration notfoundGoneDays) {

    public FetchPolicy {
        if (interval == null) {
            interval = Duration.ofMinutes(60);
        }
        if (minInterval == null) {
            minInterval = Duration.ofMinutes(5);
        }
        if (jitter == null) {
            jitter = Duration.ofSeconds(300);
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(10);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(30);
        }
        if (maxBytes <= 0) {
            maxBytes = 10 * 1024 * 1024;
        }
        if (maxRedirects < 0) {
            maxRedirects = 3;
        }
        if (perHostGap == null) {
            perHostGap = Duration.ofSeconds(30);
        }
        if (userAgent == null || userAgent.isBlank()) {
            userAgent = "rss-hub/1.0 (self-hosted; personal reader)";
        }
        if (authBackoff == null) {
            authBackoff = Duration.ofHours(24);
        }
        if (concurrency <= 0) {
            concurrency = 6;
        }
        if (backoffBase == null) {
            backoffBase = Duration.ofMinutes(5);
        }
        if (backoffCap == null) {
            backoffCap = Duration.ofHours(24);
        }
        if (notfoundGoneDays == null) {
            notfoundGoneDays = Duration.ofDays(30);
        }
    }

    /**
     * A feed's own {@code ttl} wins, but only up to the floor — 03 §4.1 says a consumer must not poll faster
     * than the source asks, and a broken feed claiming {@code ttl=0} must not be able to hot-loop.
     */
    public Duration intervalFor(Integer ttlMinutes) {
        if (ttlMinutes == null || ttlMinutes <= 0) {
            return max(minInterval, interval);
        }
        return max(minInterval, Duration.ofMinutes(ttlMinutes));
    }

    /**
     * The cadence one feed actually gets, in the order the operator expects to be obeyed: what they set by hand
     * beats what the document advertises, which beats the install default. All three are still floored at
     * {@code minInterval}, because the floor is about not hammering a host and no override is allowed to lift it.
     */
    public Duration effectiveInterval(Integer operatorMinutes, Integer ttlMinutes) {
        if (operatorMinutes == null || operatorMinutes <= 0) {
            return intervalFor(ttlMinutes);
        }
        return max(minInterval, Duration.ofMinutes(operatorMinutes));
    }

    /** The two numbers the subscription page prints next to the interval field, so its hint is this install's truth. */
    public long defaultIntervalMinutes() {
        return interval.toMinutes();
    }

    public long minIntervalMinutes() {
        return minInterval.toMinutes();
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    /**
     * 03 §4.3 step 3: {@code min(base * 2^failCount, cap)}. The shift is clamped before it is applied because
     * {@code 2^30} already exceeds any cap and a large fail count would otherwise overflow the shift.
     */
    public Duration backoffFor(int failCount) {
        int exponent = Math.min(Math.max(failCount, 1), 20);
        long seconds = backoffBase.toSeconds() << exponent;
        Duration raw = seconds <= 0 ? backoffCap : Duration.ofSeconds(seconds);
        return min(raw, backoffCap);
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
