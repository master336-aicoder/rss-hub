package com.rsshub.editorial;

import com.rsshub.feedrender.FeedKey;
import com.rsshub.feedrender.FeedScope;
import org.springframework.stereotype.Component;

/** The whole-site outlet, {@code /feed.rss} and its two siblings (03 §6.2). */
@Component
public class PostFeedSource extends AbstractPostFeedSource {

    public PostFeedSource(PostRepository posts, SiteIdentity site) {
        super(posts, site);
    }

    @Override
    public FeedScope scope() {
        return FeedScope.SITE;
    }

    @Override
    protected String tagSlug(FeedKey key) {
        return null;
    }

    @Override
    protected String categorySlug(FeedKey key) {
        return null;
    }

    @Override
    protected String sliceTitle(FeedKey key) {
        return site().siteTitle();
    }

    /**
     * A site without a configured description still has to answer RSS 2.0, which requires one. The honest fallback is
     * the sentence that describes what the outlet actually is, not an empty element.
     */
    @Override
    protected String sliceDescription(FeedKey key) {
        String configured = site().siteDescription();
        return configured == null || configured.isBlank()
            ? "New writing from " + site().siteTitle()
            : configured;
    }
}
