package com.rsshub.feedrender;

import java.util.Set;

/**
 * Identifies one public outlet: {@code blog/rss}, {@code tag/observability/atom}, {@code events/rss} (03 §6.1).
 *
 * <p>{@code slug} is null only for the site-wide outlets — {@link FeedScope#SITE} and {@link FeedScope#CHANGELOG},
 * of which a site has exactly one — and every other scope is meaningless without one. That is enforced here rather
 * than at each call site because a key that cannot exist is better than a source that has to guess.
 */
public record FeedKey(FeedScope scope, String slug) {

    private static final Set<FeedScope> SINGLETON_OUTLETS = Set.of(FeedScope.SITE, FeedScope.CHANGELOG);

    public FeedKey {
        if (SINGLETON_OUTLETS.contains(scope)) {
            if (slug != null) {
                throw new IllegalArgumentException(scope + " is one outlet for the whole site and takes no slug");
            }
        } else if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("scope " + scope + " needs a slug");
        }
    }

    public static FeedKey site() {
        return new FeedKey(FeedScope.SITE, null);
    }

    /** The site's change log (FR-1.6): one outlet, no slug, addressed as {@code /feed/changelog}.{format}. */
    public static FeedKey changelog() {
        return new FeedKey(FeedScope.CHANGELOG, null);
    }

    public static FeedKey tag(String slug) {
        return new FeedKey(FeedScope.TAG, slug);
    }

    public static FeedKey category(String slug) {
        return new FeedKey(FeedScope.CATEGORY, slug);
    }

    /**
     * The path 03 §6.2 promises, without the format suffix. The controller appends {@code .rss} and this must stay
     * in step with its mappings, because the same string is what goes into {@code atom:link rel="self"}.
     */
    public String selfPath() {
        return scope.pathFor(slug);
    }

    /** Cache/fingerprint identity, e.g. {@code site}, {@code tag/observability}. */
    public String cacheKey() {
        return scope.cacheKey(slug);
    }
}
