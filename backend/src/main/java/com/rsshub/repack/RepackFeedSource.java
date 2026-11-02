package com.rsshub.repack;

import com.rsshub.common.ApiException;
import com.rsshub.feedrender.FeedFingerprint;
import com.rsshub.feedrender.FeedItem;
import com.rsshub.feedrender.FeedKey;
import com.rsshub.feedrender.FeedMeta;
import com.rsshub.feedrender.FeedScope;
import com.rsshub.feedrender.FeedSource;
import com.rsshub.feedrender.FeedWriter;
import com.rsshub.feedrender.PlainText;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /repack/{slug}.{format}} (03 §6.2's {@code REPACK} scope): several subscribed feeds, published outward as
 * one collection — F7.
 *
 * <p>The three writers already knew how to carry this shape (Rss2Writer's {@code <source url>}, AtomWriter's
 * {@code atom:source}, JSON Feed's {@code external_url}); what a repack adds is only which rows are shipped and how
 * much of each one, so the outlet itself is a bean and nothing more (03 §6.1).
 */
@Component
public class RepackFeedSource implements FeedSource {

    private final RepackRepository repacks;

    public RepackFeedSource(RepackRepository repacks) {
        this.repacks = repacks;
    }

    @Override
    public FeedScope scope() {
        return FeedScope.REPACK;
    }

    @Override
    public FeedMeta meta(FeedKey key) {
        RepackRepository.Repack repack = require(key.slug());
        // RSS 2.0's channel description is mandatory, so a collection created without one states what it holds.
        String description = repack.description() == null || repack.description().isBlank()
            ? "Items republished from the feeds collected in " + repack.title()
            : repack.description();
        return FeedMeta.of(repack.title(), null, description);
    }

    @Override
    public List<FeedItem> items(FeedKey key, int limit) {
        require(key.slug());
        return repacks.items(key.slug(), limit).stream().map(RepackFeedSource::toItem).toList();
    }

    @Override
    public FeedFingerprint fingerprint(FeedKey key, int limit) {
        require(key.slug());
        RepackRepository.FeedStats stats = repacks.feedStats(key.slug(), limit);
        return new FeedFingerprint(stats.newestUpdated(), stats.rowCount(), stats.idDigest());
    }

    /**
     * FR-7.1's two rules in one place: the link goes to the original, and only a member that explicitly opted into
     * full text contributes a body. Everyone gets a summary — an item with nothing but a title is a teaser, not a
     * reason to fetch from the aggregator.
     */
    private static FeedItem toItem(RepackRepository.Item row) {
        String description = row.summary() == null || row.summary().isBlank()
            ? PlainText.preview(row.contentHtml())
            : row.summary();
        String body = row.fullContent() ? row.contentHtml() : null;
        return new FeedItem(stableId(row), row.url(), title(row, description), description, body, null,
            row.publishedAt(), row.updatedAt(), row.authors(), row.categories(), List.of(), null,
            row.sourceUrl(), row.sourceTitle());
    }

    /**
     * The id the original gave the item stays the item's id (03 §6.3, doc/rss-quickref.md:133): a reader that already
     * subscribes to the source must not see the same article twice under two ids. A guid that is not an absolute URL
     * cannot be published as one, and Atom's {@code <id>} has to be an IRI, so the entry's own permalink is the next
     * candidate and a minted URN is the last.
     */
    private static String stableId(RepackRepository.Item row) {
        if (FeedWriter.isUrl(row.guid())) {
            return row.guid();
        }
        if (FeedWriter.isUrl(row.url())) {
            return row.url();
        }
        return "urn:rsshub:entry:" + row.publicId();
    }

    /** Atom requires a per-entry title and a source is allowed to publish without one (V1 leaves it nullable). */
    private static String title(RepackRepository.Item row, String description) {
        if (row.title() != null && !row.title().isBlank()) {
            return row.title().trim();
        }
        String plain = PlainText.preview(description);
        if (plain != null) {
            return plain;
        }
        return row.sourceTitle() == null ? row.sourceUrl() : row.sourceTitle();
    }

    /** A collection nobody created is a 404 before any item is read, never an empty 200 feed. */
    private RepackRepository.Repack require(String slug) {
        return repacks.find(slug).orElseThrow(() -> ApiException.notFound("unknown_repack",
            "This site does not publish a repack feed " + slug));
    }
}
