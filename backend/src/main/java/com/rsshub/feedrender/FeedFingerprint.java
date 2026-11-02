package com.rsshub.feedrender;

import java.time.Instant;

/**
 * What a source knows about its own currency without rendering anything, so the ETag can be computed before the
 * body exists (03 §6.4).
 *
 * <p>03 §6.1 sketches this as a bare {@code Instant}. That is not enough: the fingerprint is defined two lines
 * further down as "max(updated_at) + count + 条目 id 集合哈希", and an Instant cannot express the other two — a rename
 * of the newest item and the arrival of a new item would collide. So the SPI returns the three parts the document
 * actually describes.
 *
 * @param idDigest stable digest over the ordered ids the outlet would emit at the requested limit; this is what
 *                 makes a retraction (same count, one id gone) change the ETag
 */
public record FeedFingerprint(Instant maxUpdatedAt, long itemCount, String idDigest) {

    /** The {@code Last-Modified} instant: a brand new site has no content, and 1970 would look like a stale cache. */
    public static FeedFingerprint empty() {
        return new FeedFingerprint(null, 0, "");
    }

    public boolean isEmpty() {
        return itemCount == 0;
    }
}
