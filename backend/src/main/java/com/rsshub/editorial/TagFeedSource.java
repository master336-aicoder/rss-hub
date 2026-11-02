package com.rsshub.editorial;

import com.rsshub.feedrender.FeedKey;
import com.rsshub.feedrender.FeedScope;
import org.springframework.stereotype.Component;

/** {@code /tag/{slug}.{format}} — one outlet per tag, discoverable from the post page's {@code rel=alternate} block. */
@Component
public class TagFeedSource extends AbstractPostFeedSource {

    public TagFeedSource(PostRepository posts, SiteIdentity site) {
        super(posts, site);
    }

    @Override
    public FeedScope scope() {
        return FeedScope.TAG;
    }

    @Override
    protected String tagSlug(FeedKey key) {
        // Looking the tag up is what makes an unknown slug a 404 before any item is read.
        return requireTag(key.slug()).slug();
    }

    @Override
    protected String categorySlug(FeedKey key) {
        return null;
    }

    @Override
    protected String sliceTitle(FeedKey key) {
        return requireTag(key.slug()).title() + " · " + site().siteTitle();
    }

    @Override
    protected String sliceDescription(FeedKey key) {
        return "Posts tagged " + requireTag(key.slug()).title() + " on " + site().siteTitle();
    }
}
