package com.rsshub.feedrender;

import java.util.List;

/**
 * The one read interface every publish shape implements (03 §6.1): posts, tag/category slices, the event stream, a
 * podcast, a repack feed. Three methods, all read-only.
 *
 * <p>Write side effects are forbidden by 02 §3 rule 3 and by the M2 gate that counts them ("匿名抓取 /feed.rss 不创建
 * session"). The practical reason is volume: a feed is hammered by crawlers and readers all day, and a GET that
 * writes turns free traffic into load.
 */
public interface FeedSource {

    /** Which {@link FeedScope} this source serves; the registry routes keys to it by that alone. */
    FeedScope scope();

    /**
     * Channel metadata. Must be complete for the scope — a missing {@code description} is a
     * {@link FeedRenderException}, not an empty element, because a feed that leaves the wire incomplete stays
     * incomplete in every reader that accepted it.
     */
    FeedMeta meta(FeedKey key);

    /** Newest first (doc/rss-guide.md:618), at most {@code limit} rows. */
    List<FeedItem> items(FeedKey key, int limit);

    /** Cheap currency check for the ETag short-circuit; must not build {@link #items}' HTML. */
    FeedFingerprint fingerprint(FeedKey key, int limit);
}
