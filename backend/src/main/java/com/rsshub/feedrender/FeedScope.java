package com.rsshub.feedrender;

/**
 * Which of the publish shapes a {@link FeedKey} addresses (03 §6.1). M2 fills in {@link #SITE}, {@link #TAG} and
 * {@link #CATEGORY}; the rest arrive with their milestones and each one is a new {@link FeedSource} bean plus its
 * path rule here, not a new writer.
 */
public enum FeedScope {

    SITE("site"),
    CHANGELOG("changelog"),
    TAG("tag"),
    CATEGORY("category"),
    EVENTS("feed"),
    SHOW("podcast"),
    REPACK("repack");

    private final String cachePrefix;

    FeedScope(String cachePrefix) {
        this.cachePrefix = cachePrefix;
    }

    /**
     * The URL 03 §6.2 fixes for the next decade, without the format suffix. The three odd ones are deliberate:
     * the site feed is {@code /feed} so {@code /feed.rss} can be the shortest possible address, an event stream
     * shares that prefix, and a podcast keeps the show slug before the word {@code feed} because that path is the
     * one already published to the directory.
     *
     * <p>{@code CHANGELOG} ignores {@code slug} because there is exactly one of them per site. It shares the
     * {@code /feed/} prefix with the event streams, so the controller needs its own literal mapping — a stream
     * actually named {@code changelog} is shadowed by this outlet, which is the price of the shorter URL.
     */
    public String pathFor(String slug) {
        return switch (this) {
            case SITE -> "/feed";
            case CHANGELOG -> "/feed/changelog";
            case TAG -> "/tag/" + slug;
            case CATEGORY -> "/category/" + slug;
            case EVENTS -> "/feed/" + slug;
            case SHOW -> "/podcast/" + slug + "/feed";
            case REPACK -> "/repack/" + slug;
        };
    }

    /** Cache/fingerprint identity, e.g. {@code site}, {@code tag/observability}. */
    public String cacheKey(String slug) {
        return slug == null ? cachePrefix : cachePrefix + "/" + slug;
    }
}
