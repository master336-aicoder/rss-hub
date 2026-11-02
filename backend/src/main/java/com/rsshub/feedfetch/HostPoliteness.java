package com.rsshub.feedfetch;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 03 §4.1: two requests to the same host must never overlap, and there must be at least
 * {@code rsshub.fetch.per-host-gap} between them. A feed that fails this is not an error — the task is simply
 * re-armed a few seconds later, so the politeness never turns into a failure counter.
 *
 * <p>State is in memory on purpose. Restarting forgets the gaps, and the worst case is one extra request per
 * host per boot; persisting a 30-second timer would cost a write per fetch.
 */
@Component
public class HostPoliteness {

    private final FetchPolicy policy;
    private final Map<String, Slot> slots = new ConcurrentHashMap<>();

    public HostPoliteness(FetchPolicy policy) {
        this.policy = policy;
    }

    private static final class Slot {
        boolean inFlight;
        Instant lastStarted = Instant.EPOCH;
    }

    /**
     * Reserves {@code host} for the current fetch.
     *
     * @return empty when the caller may proceed now (and owns the host until {@link #release}), otherwise the
     *         remaining wait before the gap is satisfied
     */
    public Optional<Instant> reserve(String host, Instant now) {
        synchronized (slots) {
            Slot slot = slots.computeIfAbsent(host, k -> new Slot());
            if (slot.inFlight) {
                // Another fetch owns this host; try again on the next dispatch round rather than guessing a time.
                return Optional.of(now.plusSeconds(2));
            }
            Instant earliest = slot.lastStarted.plus(policy.perHostGap());
            if (now.isBefore(earliest)) {
                return Optional.of(earliest);
            }
            slot.inFlight = true;
            slot.lastStarted = now;
            return Optional.empty();
        }
    }

    /** Hands the host back. Always call this in a finally block or the host is starved until restart. */
    public void release(String host) {
        synchronized (slots) {
            Slot slot = slots.get(host);
            if (slot != null) {
                slot.inFlight = false;
            }
        }
    }
}
