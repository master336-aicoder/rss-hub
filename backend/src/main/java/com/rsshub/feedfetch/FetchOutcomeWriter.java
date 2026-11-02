package com.rsshub.feedfetch;

import com.rsshub.feedfetch.FetchReport.Arm;
import com.rsshub.feedfetch.FetchReport.Feed;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 03 §4.2 and §4.3 step 7: the feed's health columns and the task's next {@code fire_at} are written together or
 * not at all. It is a separate bean because {@code @Transactional} only applies through the proxy, and the caller
 * that decides what to write must not be the one that opens the transaction.
 */
@Service
public class FetchOutcomeWriter {

    private final FeedFetchRepository feeds;
    private final TaskQueue queue;
    private final RawResponseStore raw;

    public FetchOutcomeWriter(FeedFetchRepository feeds, TaskQueue queue, RawResponseStore raw) {
        this.feeds = feeds;
        this.queue = queue;
        this.raw = raw;
    }

    /**
     * An exchange that produced content. The counters reset here, not in the caller: "this fetch worked" is a
     * storage-level fact with exactly one definition.
     *
     * @param rawId the {@code raw_response} row already written, or null when nothing was retained (304)
     */
    @Transactional
    public void applyContent(long feedId, long taskId, Long rawId, Integer entryCount, Feed feed, Arm arm) {
        feeds.recordContent(feedId, feed.state(), feed.httpStatus(), feed.error(), feed.etag(), feed.lastModified(),
            feed.canonicalUrl(), feed.elapsedMs());
        if (feed.contentChanged()) {
            feeds.markChanged(feedId, Instant.now());
        }
        if (rawId != null && entryCount != null) {
            raw.attachEntryCount(rawId, entryCount);
        }
        arm(taskId, arm);
    }

    /** An exchange that must not pretend to be healthy: counters are written exactly as decided. */
    @Transactional
    public void applyFailure(long feedId, long taskId, Feed feed, Arm arm) {
        feeds.recordFetch(feedId, feed.state(), feed.httpStatus(), feed.failCount(), feed.notfoundStreak(),
            feed.error(), feed.etag(), feed.lastModified());
        arm(taskId, arm);
    }

    private void arm(long taskId, Arm arm) {
        if (arm.cancel()) {
            queue.cancel(taskId, arm.reason());
        } else if (arm.backoff()) {
            queue.armBackoff(taskId, arm.fireAt(), arm.reason());
        } else {
            queue.armNext(taskId, arm.fireAt());
        }
    }
}
