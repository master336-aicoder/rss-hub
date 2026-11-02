package com.rsshub.adminapi;

import com.rsshub.changelog.ChangeLogRepository;
import com.rsshub.editorial.PostRepository;
import com.rsshub.feedstore.EntryFinder;
import com.rsshub.feedstore.EntryFinder.CardEntry;
import com.rsshub.feedstore.FeedRepository;
import com.rsshub.feedstore.FeedRepository.FeedCard;
import com.rsshub.rules.NotificationRepository;
import com.rsshub.settings.SettingsService;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FR-8's wall screen. One card per feed with that feed's newest {@code dashboard.entriesPerFeed} entries, and the
 * rails around it that turn a list of cards into something readable from across a room: the last 24 hours, the
 * folders, the alerts the rules fired, and what this site published itself.
 *
 * <p>Every number is a projection of state another module already owns — the aggregates are read where the rows
 * live, and this class only folds them together. That is what keeps a wall and the page it summarises from
 * disagreeing only when the two were read a refresh apart, which is the honest answer for a screen that repaints.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    /**
     * The wall's rolling window: the {@code WINDOW_HOURS} whole hours ending with the current one. It is anchored to
     * the hour rather than to {@code now} so the histogram and the scalar it adds up to are the same set of rows —
     * a window that slid past a partial bucket would make "24 小时到达" disagree with the bars beside it.
     */
    private static final int WINDOW_HOURS = 24;
    private static final int ALERT_ROWS = 10;
    private static final int POST_ROWS = 6;
    private static final int CHANGE_ROWS = 6;

    private final FeedRepository feeds;
    private final EntryFinder entries;
    private final NotificationRepository notifications;
    private final PostRepository posts;
    private final ChangeLogRepository changes;
    private final SettingsService settings;

    public DashboardController(FeedRepository feeds, EntryFinder entries, NotificationRepository notifications,
        PostRepository posts, ChangeLogRepository changes, SettingsService settings) {
        this.feeds = feeds;
        this.entries = entries;
        this.notifications = notifications;
        this.posts = posts;
        this.changes = changes;
        this.settings = settings;
    }

    /**
     * @param lastFetchedAt when ingest last touched anything; a wall that is hours behind its sources is showing a
     *        fetch problem, not a quiet news day
     * @param hourly the {@code WINDOW_HOURS} hour buckets, oldest first — always the same count, so the bars cannot
     *        shuffle as the window slides
     * @param groups the folders, busiest first; the empty name is the pile of subscriptions nobody filed
     */
    public record DashboardView(int entriesPerFeed, int refreshSeconds, Instant generatedAt, Instant lastFetchedAt,
        StatsView stats, List<HourBucketView> hourly, List<GroupView> groups, List<FeedCardView> feeds,
        List<AlertView> alerts, List<PostView> posts, List<ChangeView> changes) {
    }

    /**
     * The headline numbers, all of them over the wall's rolling window except the four that are plainly totals.
     *
     * @param problemFeeds how many sources are failing or have an error the last round recorded
     * @param quietFeeds how many have not been fetched at all in the window — the two counts differ on purpose,
     *        because a source that simply is not due yet is not the same news as one that keeps erroring
     */
    public record StatsView(long feeds, long entries, long unread, long publishedPosts, long arrivedToday,
        long hitsToday, long deliveredToday, long failedToday, long problemFeeds, long quietFeeds) {
    }

    public record HourBucketView(Instant hour, long arrivals, long hits) {
    }

    /**
     * @param name the folder label; empty means "no group"
     */
    public record GroupView(String name, long feeds, long unread, long arrivedToday) {
    }

    /**
     * A card is the source plus its newest lines. The failure fields travel with it because the wall's job is to show
     * a source that stopped working without the operator having to open the health page to find out.
     */
    public record FeedCardView(long id, String title, String groupName, String state, long unread, int failCount,
        String lastError, Instant lastFetchedAt, Instant lastChangeAt, Instant nextFetchAt,
        List<CardEntryView> entries) {
    }

    public record CardEntryView(long id, String title, String url, Instant publishedAt, String readState) {
    }

    public record AlertView(long id, String ruleName, String state, String channel, Instant createdAt, Long entryId,
        String entryTitle) {
    }

    public record PostView(long id, String slug, String title, Instant publishedAt) {
    }

    public record ChangeView(long id, String title, String url, String action, Instant changedAt) {
    }

    @GetMapping
    public DashboardView dashboard() {
        int perFeed = settings.intSetting(SettingsService.DASHBOARD_ENTRIES_PER_FEED);
        Instant now = Instant.now();
        Instant since = now.truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(WINDOW_HOURS - 1L));

        Map<Long, List<CardEntryView>> byFeed = new LinkedHashMap<>();
        for (CardEntry row : entries.latestPerFeed(perFeed)) {
            byFeed.computeIfAbsent(row.feedId(), feedId -> new ArrayList<>())
                .add(new CardEntryView(row.id(), row.title(), row.url(), row.publishedAt(), row.readState()));
        }
        Map<Long, Long> unread = entries.unreadByFeed();
        List<FeedCard> cards = feeds.listCards();
        List<FeedCardView> feedViews = new ArrayList<>();
        for (FeedCard feed : cards) {
            feedViews.add(new FeedCardView(feed.id(), feed.title(), blankToNull(feed.groupName()), feed.state(),
                unread.getOrDefault(feed.id(), 0L), feed.failCount(), feed.lastError(), feed.lastFetchedAt(),
                feed.lastChangeAt(), feed.nextFetchAt(), byFeed.getOrDefault(feed.id(), List.of())));
        }

        return new DashboardView(perFeed, settings.intSetting(SettingsService.DASHBOARD_REFRESH_SECONDS), now,
            entries.lastFetchedAt().orElse(null), stats(feedViews, since), buckets(since),
            folders(feedViews, since), feedViews, alerts(), publishedPosts(), recentChanges());
    }

    private StatsView stats(List<FeedCardView> views, Instant since) {
        Map<String, Long> notificationStates = notifications.countsSince(since);
        EntryFinder.Counters counters = entries.counters();
        long problems = views.stream().filter(DashboardController::isProblem).count();
        long quiet = views.stream().filter(feed -> feed.lastFetchedAt() == null
            || feed.lastFetchedAt().isBefore(since)).count();
        return new StatsView(views.size(), counters.total(), counters.unread(), posts.countPublished(),
            entries.arrivedSince(since), sum(notificationStates), notificationStates.getOrDefault(
                NotificationRepository.SENT, 0L), notificationStates.getOrDefault(
                NotificationRepository.FAILED, 0L), problems, quiet);
    }

    /** A source the operator would want looked at: it is in a failure state, or the last round left an error. */
    private static boolean isProblem(FeedCardView feed) {
        return feed.failCount() > 0 || feed.lastError() != null
            || "error".equals(feed.state()) || "gone".equals(feed.state());
    }

    /**
     * The histogram, always 24 buckets. Sparse rows come back from both queries and are folded here — an hour with
     * nothing in it is a bar of zero height, not a missing bar, because a wall with shifting bar counts next to a
     * fixed axis is unreadable.
     */
    private List<HourBucketView> buckets(Instant since) {
        Map<Instant, Long> arrivals = entries.arrivalsByHour(since);
        Map<Instant, Long> hits = notifications.hitsByHour(since);
        List<HourBucketView> buckets = new ArrayList<>(WINDOW_HOURS);
        for (int hour = 0; hour < WINDOW_HOURS; hour++) {
            Instant start = since.plus(Duration.ofHours(hour));
            buckets.add(new HourBucketView(start, arrivals.getOrDefault(start, 0L), hits.getOrDefault(start, 0L)));
        }
        return buckets;
    }

    private List<GroupView> folders(List<FeedCardView> views, Instant since) {
        Map<String, Long> arrived = entries.arrivedByGroupSince(since);
        Map<String, long[]> rolled = new LinkedHashMap<>();
        for (FeedCardView feed : views) {
            long[] totals = rolled.computeIfAbsent(feed.groupName() == null ? "" : feed.groupName(),
                name -> new long[2]);
            totals[0]++;
            totals[1] += feed.unread();
        }
        List<GroupView> groups = new ArrayList<>();
        rolled.forEach((name, totals) -> groups.add(
            new GroupView(name, totals[0], totals[1], arrived.getOrDefault(name, 0L))));
        groups.sort((left, right) -> {
            int byFeeds = Long.compare(right.feeds(), left.feeds());
            return byFeeds != 0 ? byFeeds : left.name().compareTo(right.name());
        });
        return List.copyOf(groups);
    }

    private List<AlertView> alerts() {
        return notifications.recent(ALERT_ROWS).stream()
            .map(alert -> new AlertView(alert.id(), alert.ruleName(), alert.state(), alert.channel(),
                alert.createdAt(), alert.entryId(), alert.entryTitle()))
            .toList();
    }

    private List<PostView> publishedPosts() {
        return posts.recentPublished(POST_ROWS).stream()
            .map(post -> new PostView(post.id(), post.slug(), post.title(), post.publishedAt()))
            .toList();
    }

    private List<ChangeView> recentChanges() {
        return changes.list(CHANGE_ROWS).stream()
            .map(change -> new ChangeView(change.id(), change.title(), change.url(), change.action(),
                change.changedAt()))
            .toList();
    }

    private static long sum(Map<String, Long> counts) {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
