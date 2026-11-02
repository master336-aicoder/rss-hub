package com.rsshub.changelog;

import com.rsshub.changelog.ChangeLogRepository.Change;
import com.rsshub.feedrender.FeedFingerprint;
import com.rsshub.feedrender.FeedItem;
import com.rsshub.feedrender.FeedKey;
import com.rsshub.feedrender.FeedMeta;
import com.rsshub.feedrender.FeedScope;
import com.rsshub.feedrender.FeedSource;
import com.rsshub.feedrender.PublishProperties;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /feed/changelog.{format}} (FR-1.6): the site's own edit history published outward as a feed, so a reader can
 * subscribe to "what changed here" instead of re-reading the archive.
 *
 * <p>It is a {@link FeedSource} and nothing more — one bean plus the path rule, the same shape M3's event outlet and
 * M5's repack outlet took. The rows come from {@link ChangeLogRepository}, which the editorial service writes; this
 * class only decides what one change looks like in three serialisations.
 */
@Component
public class ChangeLogFeedSource implements FeedSource {

    /**
     * The outlet's channel title, and the label every {@code rel=alternate} link uses for it. Composed here because
     * the page that advertises the outlet and the outlet itself must not disagree about what it is called.
     */
    public static String outletTitle(String siteTitle) {
        return "Changelog · " + siteTitle;
    }

    /** RSS 2.0 requires a channel description, and this outlet has exactly one kind of content, so it can be stated. */
    private static final String DESCRIPTION =
        "Every public change this site has made to its posts: new articles, edits, status moves and retractions.";

    private final ChangeLogRepository changes;
    private final PublishProperties publish;

    public ChangeLogFeedSource(ChangeLogRepository changes, PublishProperties publish) {
        this.changes = changes;
        this.publish = publish;
    }

    @Override
    public FeedScope scope() {
        return FeedScope.CHANGELOG;
    }

    /** {@code siteUrl} stays null so the renderer fills the configured site origin, and the self URL comes from the key. */
    @Override
    public FeedMeta meta(FeedKey key) {
        return new FeedMeta(outletTitle(publish.siteTitle()), null, DESCRIPTION, publish.language(),
            publish.ttlMinutes(), null, null, null, null);
    }

    @Override
    public List<FeedItem> items(FeedKey key, int limit) {
        return changes.list(limit).stream().map(ChangeLogFeedSource::toItem).toList();
    }

    @Override
    public FeedFingerprint fingerprint(FeedKey key, int limit) {
        ChangeLogRepository.FeedStats stats = changes.feedStats(limit);
        return new FeedFingerprint(stats.newestUpdated(), stats.rowCount(), stats.idDigest());
    }

    /**
     * Summary plus backlink, never a body. A change record is a pointer to the article, and shipping the article's
     * text here would put two copies of it on the wire under two different ids — the reader's "已读" state belongs to
     * the post's own permalink, and that is where the link goes (03 §6.3 rule 2's reasoning, applied to the
     * opposite direction).
     */
    private static FeedItem toItem(Change row) {
        return new FeedItem(id(row), row.url(), title(row), description(row), null, null, row.changedAt(),
            row.changedAt(), List.of(), List.of(row.action()), List.of(), null, null, null);
    }

    /**
     * The post's permalink cannot be this item's id: one article produces many changes, and a feed id has to be
     * unique per entry. The change's own uuid, minted once at insert, is stable across every later edit of the title
     * or the body, which is what FR-1.5 asks of an id.
     */
    private static String id(Change row) {
        return "urn:rsshub:change:" + row.publicId();
    }

    private static String title(Change row) {
        return switch (row.action()) {
            case ChangeLogRepository.CREATED -> "New post: " + row.title();
            case ChangeLogRepository.UPDATED -> "Updated: " + row.title();
            case ChangeLogRepository.DELETED -> "Deleted: " + row.title();
            default -> statusWord(row.toStatus()) + ": " + row.title();
        };
    }

    /**
     * "Status changed: X" tells a subscriber nothing; the direction is the news. This is the one place the outlet
     * speaks, so the words live here rather than in the row.
     */
    private static String statusWord(String toStatus) {
        return switch (toStatus == null ? "" : toStatus) {
            case "published" -> "Published";
            case "unlisted" -> "Taken off the lists";
            case "scheduled" -> "Scheduled";
            case "archived" -> "Archived";
            default -> "Back to draft";
        };
    }

    private static String description(Change row) {
        return switch (row.action()) {
            case ChangeLogRepository.CREATED -> "A new post went out at " + row.url();
            case ChangeLogRepository.UPDATED -> "The post was edited; the text behind this link is no longer the one"
                + " first published.";
            case ChangeLogRepository.DELETED -> "The post was removed. Its URL " + row.url() + " no longer resolves,"
                + " and this entry is the retraction notice.";
            default -> "The post moved from " + row.fromStatus() + " to " + row.toStatus() + ".";
        };
    }
}
