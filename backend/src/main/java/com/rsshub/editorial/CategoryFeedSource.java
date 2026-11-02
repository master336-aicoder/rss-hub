package com.rsshub.editorial;

import com.rsshub.feedrender.FeedKey;
import com.rsshub.feedrender.FeedScope;
import org.springframework.stereotype.Component;

/**
 * {@code /category/{slug}.{format}}. A category differs from a tag in exactly one way that matters to a subscriber:
 * it is the operator's own taxonomy, so the outlet is expected to be stable, whereas a tag outlet can disappear the
 * moment the last post carrying it is deleted.
 */
@Component
public class CategoryFeedSource extends AbstractPostFeedSource {

    public CategoryFeedSource(PostRepository posts, SiteIdentity site) {
        super(posts, site);
    }

    @Override
    public FeedScope scope() {
        return FeedScope.CATEGORY;
    }

    @Override
    protected String tagSlug(FeedKey key) {
        return null;
    }

    @Override
    protected String categorySlug(FeedKey key) {
        return requireCategory(key.slug()).slug();
    }

    @Override
    protected String sliceTitle(FeedKey key) {
        return requireCategory(key.slug()).title() + " · " + site().siteTitle();
    }

    @Override
    protected String sliceDescription(FeedKey key) {
        return "Posts in " + requireCategory(key.slug()).title() + " on " + site().siteTitle();
    }
}
