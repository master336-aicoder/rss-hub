package com.rsshub.adminapi;

import com.rsshub.feedstore.EntryFinder;
import com.rsshub.feedstore.FeedRepository;
import com.rsshub.feedstore.FeedRepository.AdminFeed;
import com.rsshub.rules.RuleService;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FR-2.7: the page that answers "why has this source not updated". Every field here is a column the fetcher already
 * maintains — this endpoint adds nothing but the ordering (noisy first) and two derived numbers, because a health
 * view that recomputes state would be a second, disagreeing definition of it.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final FeedRepository feeds;
    private final EntryFinder entries;
    private final RuleService rules;

    public HealthController(FeedRepository feeds, EntryFinder entries, RuleService rules) {
        this.feeds = feeds;
        this.entries = entries;
        this.rules = rules;
    }

    /**
     * @param quietFor how long since the source last had real content; null when it has never produced a change
     * @param nextFetchAt when the queue will try it again, or null when nothing is queued (paused, or gone)
     * @param problem the one flag the page sorts by: anything that needs reading, not merely anything unusual
     */
    public record FeedHealth(long id, String title, String canonicalUrl, String groupName, String format,
        String state, int failCount, int notfoundStreak, Integer lastHttpStatus, String lastError,
        Instant lastFetchedAt, Instant lastChangeAt, Long quietForSeconds, Integer avgFetchMs, Instant nextFetchAt,
        long unreadCount, long entryCount, boolean problem) {
    }

    /**
     * @param failing anything with a failure counted or a terminal state
     * @param quiet anything that has not produced new content in three days or more
     */
    public record Summary(int total, Map<String, Integer> byState, int failing, int quiet, Long slowestFetchMs) {
    }

    /**
     * @param feeds the sources, noisy first
     * @param summary the numbers on top of the page
     * @param rulesDisabled how many rules the delivery circuit breaker stopped (03 §8.2's red hint). Anything above
     *        zero means an alert pipeline that has gone quiet by itself, which is exactly the kind of fact a health
     *        page exists to make impossible to miss.
     */
    public record HealthView(List<FeedHealth> feeds, Summary summary, int rulesDisabled) {
    }

    @GetMapping("/feeds")
    public HealthView feedHealth() {
        Instant now = Instant.now();
        List<FeedHealth> rows = feeds.listAdmin().stream()
            .map(feed -> health(feed, now))
            // Problems first, then the sources that have been quiet longest: the two questions the page gets asked
            // are "what is broken" and "what has stopped", in that order.
            .sorted(Comparator.comparing((FeedHealth row) -> !row.problem())
                .thenComparing(FeedHealth::quietForSeconds, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(FeedHealth::id))
            .toList();
        return new HealthView(rows, summary(rows), rules.autoDisabledCount());
    }

    private static FeedHealth health(AdminFeed feed, Instant now) {
        Long quiet = feed.lastChangeAt() == null ? null : Duration.between(feed.lastChangeAt(), now).getSeconds();
        boolean problem = feed.failCount() > 0 || feed.lastError() != null
            || feed.state().equals("error") || feed.state().equals("gone");
        return new FeedHealth(feed.id(), feed.displayTitle(), feed.canonicalUrl(), feed.groupName(), feed.format(),
            feed.state(), feed.failCount(), feed.notfoundStreak(), feed.lastHttpStatus(), feed.lastError(),
            feed.lastFetchedAt(), feed.lastChangeAt(), quiet, feed.avgFetchMs(), feed.nextFetchAt(),
            feed.unreadCount(), feed.entryCount(), problem);
    }

    private static Summary summary(List<FeedHealth> rows) {
        Map<String, Integer> byState = new LinkedHashMap<>();
        int failing = 0;
        int quiet = 0;
        Long slowest = null;
        for (FeedHealth row : rows) {
            byState.merge(row.state(), 1, Integer::sum);
            if (row.failCount() > 0 || row.state().equals("error") || row.state().equals("gone")) {
                failing++;
            }
            // "Quiet" is a three-day threshold with no configuration: FR-2.7 wants a number to sort by, and the
            // operator who cares can read the per-feed seconds column underneath it.
            if (row.quietForSeconds() != null && row.quietForSeconds() > 3 * 24 * 3600) {
                quiet++;
            }
            if (row.avgFetchMs() != null && (slowest == null || row.avgFetchMs() > slowest)) {
                slowest = (long) row.avgFetchMs();
            }
        }
        return new Summary(rows.size(), byState, failing, quiet, slowest);
    }
}
