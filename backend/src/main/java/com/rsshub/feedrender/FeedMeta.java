package com.rsshub.feedrender;

import java.time.Instant;

/**
 * Channel-level metadata for one outlet. The three fields the RSS 2.0 DTD requires — {@code title}, {@code link},
 * {@code description} (doc/rss-quickref.md:21) — are checked by the writers, not defaulted here, because "the
 * source is incomplete" has to be a loud failure rather than an empty {@code <description/>} that a lenient reader
 * swallows and a validator rejects.
 *
 * <p>No WebSub: {@code atom:link rel="hub"} is deliberately absent, per the scope decision in 01 §2.
 *
 * @param selfUrl     absolute {@code atom:link rel="self"}; null lets the renderer build it from the {@link FeedKey}
 * @param lastBuildAt {@code lastBuildDate}; null is omitted rather than faked with "now", which would make every
 *                    response look freshly built and defeat the point of the header
 */
public record FeedMeta(String title, String siteUrl, String description, String language, Integer ttlMinutes,
    String imageUrl, String selfUrl, Instant lastBuildAt, String generator) {

    public static FeedMeta of(String title, String siteUrl, String description) {
        return new FeedMeta(title, siteUrl, description, null, null, null, null, null, null);
    }

    public FeedMeta withSelfUrl(String absoluteSelfUrl) {
        return new FeedMeta(title, siteUrl, description, language, ttlMinutes, imageUrl, absoluteSelfUrl, lastBuildAt,
            generator);
    }

    public FeedMeta withLastBuildAt(Instant value) {
        return new FeedMeta(title, siteUrl, description, language, ttlMinutes, imageUrl, selfUrl, value, generator);
    }
}
