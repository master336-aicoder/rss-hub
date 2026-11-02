package com.rsshub.feedstore;

import java.time.Instant;
import java.util.List;

/**
 * The fact that one entry became new to this instance (03 §8.1's trigger).
 *
 * <p>It is published inside the ingest transaction, which is the whole point: 03 §8.1 asks the rule engine to
 * record "this entry hit that rule" in the same transaction that stored the entry, so an alert cannot exist for an
 * entry that was rolled back, and a rolled-back fetch cannot leave an orphan alert behind. Delivery is deliberately
 * not part of this — it happens later, in its own transaction.
 *
 * <p>Two producers, one shape: {@link EntryIngestService} publishes it for a subscription entry, and the event
 * stream bridge publishes it for a machine event that was bridged into the inbox, so a rule cannot tell the two
 * kinds of source apart by anything but {@code groupName}.
 */
public record EntryNewEvent(long entryId, long feedId, String feedTitle, String groupName, String url, String title,
    String summary, String contentHash, List<String> tags, Instant publishedAt, Instant fetchedAt) {

    /** The instant a rule's {@code windowMinutes} measures against: the source date when there is one. */
    public Instant effectiveTime() {
        return publishedAt == null ? fetchedAt : publishedAt;
    }
}
