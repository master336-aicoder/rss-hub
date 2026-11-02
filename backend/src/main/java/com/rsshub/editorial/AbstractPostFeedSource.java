package com.rsshub.editorial;

import com.rsshub.common.ApiException;
import com.rsshub.editorial.PostRepository.FeedRow;
import com.rsshub.editorial.PostRepository.FeedStats;
import com.rsshub.editorial.PostRepository.PostRow;
import com.rsshub.editorial.PostRepository.Term;
import com.rsshub.feedrender.FeedFingerprint;
import com.rsshub.feedrender.FeedItem;
import com.rsshub.feedrender.FeedKey;
import com.rsshub.feedrender.FeedMeta;
import com.rsshub.feedrender.FeedSource;
import java.util.List;

/**
 * Turns the post table into a {@code FeedSource}. One subclass per outlet (03 §6.1 names them separately) because the
 * registry routes by scope, and one bean cannot claim three of them.
 *
 * <p>What the outlets share is everything an author does not set per slice: which rows are visible, what an item's
 * permanent URL is, and how the digest is built. A subclass answers "which slice" and nothing else.
 */
abstract class AbstractPostFeedSource implements FeedSource {

    private final PostRepository posts;
    private final SiteIdentity site;

    AbstractPostFeedSource(PostRepository posts, SiteIdentity site) {
        this.posts = posts;
        this.site = site;
    }

    /** Null means "the whole site"; the two term subclasses answer with their key's slug. */
    protected abstract String tagSlug(FeedKey key);

    protected abstract String categorySlug(FeedKey key);

    protected abstract String sliceTitle(FeedKey key);

    protected abstract String sliceDescription(FeedKey key);

    /** The site description is optional in config but mandatory in RSS 2.0, so a slice always states what it holds. */
    @Override
    public FeedMeta meta(FeedKey key) {
        return new FeedMeta(sliceTitle(key), site.baseUrl(), sliceDescription(key), site.language(), null,
            site.imageUrl(), null, null, null);
    }

    @Override
    public List<FeedItem> items(FeedKey key, int limit) {
        return posts.feedRows(tagSlug(key), categorySlug(key), limit).stream()
            .map(this::toItem)
            .toList();
    }

    @Override
    public FeedFingerprint fingerprint(FeedKey key, int limit) {
        FeedStats stats = posts.feedStats(tagSlug(key), categorySlug(key), limit);
        return new FeedFingerprint(stats.newestUpdated(), stats.rowCount(), stats.idDigest());
    }

    /**
     * The item's permanent ID is its own page, not the site root and not the database row (doc/rss-quickref.md:133):
     * editing a title changes the {@code <title>} element and nothing else, because the slug — and therefore the ID —
     * was fixed when the post was created.
     */
    private FeedItem toItem(FeedRow row) {
        PostRow post = row.post();
        String description = post.summaryMd() == null || post.summaryMd().isBlank()
            ? MarkdownRenderer.summarize(post.bodyHtml(), 300)
            : MarkdownRenderer.toHtml(post.summaryMd());
        return new FeedItem(site.postUrl(post.slug()), permalink(post), post.title(), description, post.bodyHtml(),
            null, post.publishedAt() == null ? post.updatedAt() : post.publishedAt(), post.updatedAt(),
            List.of(), row.tags(), List.of(), null, null, null);
    }

    /** F7 gives the original's URL when a post is a republication; until then the permalink is the only candidate. */
    private String permalink(PostRow post) {
        return post.canonicalUrl() == null || post.canonicalUrl().isBlank()
            ? site.postUrl(post.slug())
            : post.canonicalUrl();
    }

    /** A typo in a link is a 404, not an empty feed: a slice that does not exist must not answer 200. */
    protected Term requireTag(String slug) {
        return posts.findTag(slug).orElseThrow(() -> ApiException.notFound("unknown_tag", "No tag " + slug));
    }

    protected Term requireCategory(String slug) {
        return posts.findCategory(slug)
            .orElseThrow(() -> ApiException.notFound("unknown_category", "No category " + slug));
    }

    protected SiteIdentity site() {
        return site;
    }
}
