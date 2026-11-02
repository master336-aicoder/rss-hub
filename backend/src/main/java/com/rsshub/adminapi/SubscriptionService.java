package com.rsshub.adminapi;

import com.rsshub.adminapi.Opml.Parsed;
import com.rsshub.common.ApiException;
import com.rsshub.feedfetch.FeedDiscoverer;
import com.rsshub.feedfetch.FeedDiscoverer.Discovery;
import com.rsshub.feedfetch.FeedFetchRepository;
import com.rsshub.feedfetch.FetchQueueSeeder;
import com.rsshub.feedfetch.FetchWakeup;
import com.rsshub.feedfetch.TaskQueue;
import com.rsshub.feedstore.FeedRepository;
import com.rsshub.feedstore.FeedRepository.AdminFeed;
import com.rsshub.feedstore.FeedRepository.Subscription;
import com.rsshub.security.SsrfGuard;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The subscription use cases FR-2.1 asks for: add (with 03 §4.4 discovery), edit, remove, refresh, and OPML in/out.
 *
 * <p>This lives in {@code adminapi} rather than in {@code feedstore} because it is the composition the REST surface
 * needs and nothing else does — the fetcher never adds a subscription, and the parser never deletes one. {@code
 * feedstore} keeps owning the SQL, and the leaf rule (02 §3) is what makes that ordering provable rather than a
 * convention.
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    /** The states an operator may set by hand. {@code error} and {@code gone} belong to the fetcher, not to us. */
    private static final Set<String> OPERATOR_STATES = Set.of("active", "muted", "paused");

    /** Upper bound on a hand-set cadence: 30 days, the same horizon after which a 404ing feed is declared gone. */
    private static final int MAX_INTERVAL_MINUTES = 60 * 24 * 30;

    /** Matches {@code feed.custom_title}; the store truncates, but a patch that is silently cut is not a patch. */
    private static final int MAX_TITLE_LENGTH = 512;

    /** The whole of what a subscription patch may ask for. Anything else is refused, so the address cannot be in it. */
    private static final Set<String> EDITABLE_FIELDS = Set.of("title", "group", "state", "intervalMinutes");

    private final FeedRepository feeds;
    private final FeedFetchRepository fetchFeeds;
    private final FeedDiscoverer discoverer;
    private final TaskQueue queue;
    private final SsrfGuard guard;
    /** The dispatcher when the scheduler runs in this process, and a no-op when it does not (tests, {@code enabled=false}). */
    private final FetchWakeup wakeup;

    public SubscriptionService(FeedRepository feeds, FeedFetchRepository fetchFeeds, FeedDiscoverer discoverer,
        TaskQueue queue, SsrfGuard guard, ObjectProvider<FetchWakeup> dispatcher) {
        this.feeds = feeds;
        this.fetchFeeds = fetchFeeds;
        this.discoverer = discoverer;
        this.queue = queue;
        this.guard = guard;
        this.wakeup = dispatcher.getIfAvailable(() -> FetchWakeup.DISABLED);
    }

    /** What an OPML import did. Skipped URLs are counted, never listed: an import of 200 feeds is not a log line. */
    public record ImportResult(int imported, int skipped) {
    }

    /**
     * Resolves a pasted address without writing anything, so the UI can show the choice before it commits. Discovery
     * is a network operation and this endpoint is only ever called from a button.
     */
    public Discovery discover(String url) {
        return discoverer.discover(normalise(url));
    }

    /**
     * Adds a subscription and puts its first fetch on the queue.
     *
     * <p>The URL is validated twice on purpose: once here, so a typo or an intranet address is refused before
     * anything is written, and once again inside {@link FeedDiscoverer} on every hop. The first is for the operator's
     * benefit, the second is the one that actually protects the process.
     */
    @Transactional
    public AdminFeed add(String url, String group) {
        String requested = normalise(url);
        guard.validate(requested);
        Discovery discovery = discoverer.discover(requested);
        if (!discovery.found()) {
            throw ApiException.badRequest("no_feed_found",
                discovery.note() == null ? "That address is not a feed" : discovery.note());
        }
        String canonical = discovery.feedUrl();
        if (feeds.urlExists(canonical)) {
            throw ApiException.conflict("already_subscribed", "This feed is already in the list");
        }

        long id = feeds.create(requested, canonical, blankToNull(group));
        armNow(id);
        log.info("subscribed feed {} to {}", id, canonical);
        return feeds.findAdmin(id).orElseThrow(
            () -> ApiException.notFound("feed_not_found", "The subscription disappeared while it was being added"));
    }

    /**
     * Applies an operator edit to one subscription.
     *
     * <p>Only four keys exist, and a patch that asks for anything else is refused rather than ignored — above all
     * {@code canonicalUrl} and {@code requestedUrl}. Those two are the subscription's identity: the entries, the
     * queue row and its dedupe key are all attached to them, so an edit that silently did nothing to the address
     * while answering 200 would be the one response an operator could not distinguish from a move that worked.
     *
     * <p>A key that is absent leaves its column alone; {@code ""} (title, group) and {@code 0} (interval) are the
     * spellings of "stop overriding, use what the site says". The cadence has its own column because sharing
     * {@code ttl_minutes} with the publisher meant every fetched round wrote the site's number over the operator's.
     */
    public void update(long id, Map<String, Object> patch) {
        require(id);
        for (String key : patch.keySet()) {
            if (!EDITABLE_FIELDS.contains(key)) {
                throw ApiException.badRequest("unknown_field",
                    key + " is not editable on a subscription (the address never is)");
            }
        }
        String title = text(patch, "title");
        String group = text(patch, "group");
        String state = text(patch, "state");
        Integer interval = wholeNumber(patch, "intervalMinutes");

        if (state != null && !OPERATOR_STATES.contains(state)) {
            throw ApiException.badRequest("bad_state", "Only active, muted and paused are operator-settable");
        }
        if (interval != null && (interval < 0 || interval > MAX_INTERVAL_MINUTES)) {
            throw ApiException.badRequest("bad_interval",
                "interval must be 0 (follow the site) or between 1 minute and 30 days");
        }
        if (title != null && title.length() > MAX_TITLE_LENGTH) {
            throw ApiException.badRequest("bad_title", "title must be at most " + MAX_TITLE_LENGTH + " characters");
        }

        feeds.update(id, group, state, title, interval);
        if ("paused".equals(state)) {
            queue.cancelByKey("fetch", FetchQueueSeeder.dedupeKey(id), "operator paused the subscription");
        } else if (state != null) {
            // Coming back from paused has to re-arm the row, or the feed looks active and never fetches.
            armNow(id);
        }
    }

    /** A present key is a String or a JSON null (which means "unchanged", the same as absent); anything else is a
     * client that guessed the type. */
    private static String text(Map<String, Object> patch, String key) {
        Object value = patch.get(key);
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw ApiException.badRequest("bad_" + key, key + " must be text");
    }

    private static Integer wholeNumber(Map<String, Object> patch, String key) {
        Object value = patch.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Integer number)) {
            throw ApiException.badRequest("bad_" + key, key + " must be a whole number of minutes");
        }
        return number;
    }

    @Transactional
    public void delete(long id) {
        require(id);
        feeds.delete(id);
        log.info("removed subscription {}", id);
    }

    /**
     * Brings the next fetch forward. A feed in {@code gone} or {@code paused} is refused rather than silently
     * ignored, because the operator pressing the button believes it is alive.
     */
    public void refresh(long id) {
        require(id);
        if (!fetchFeeds.isSchedulable(id)) {
            throw ApiException.conflict("not_schedulable", "This subscription is paused or gone; set it active first");
        }
        armNow(id);
    }

    /**
     * Puts this feed's work at the front of the queue and asks for a round now rather than at the scheduler's next
     * tick. The wake is deferred to after commit because the row this method just wrote is not yet visible to the
     * dispatcher's own connection: waking inside the transaction would scan a queue that still looks empty, and the
     * feed would sit until the fifteen-second round anyway — which is the delay this exists to remove.
     */
    private void armNow(long feedId) {
        queue.armNow("fetch", FetchQueueSeeder.dedupeKey(feedId), feedId, Instant.now());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    wakeup.wake();
                }
            });
        } else {
            wakeup.wake();
        }
    }

    public List<Subscription> subscriptions() {
        return feeds.listSubscriptions();
    }

    /**
     * Imports an OPML document. Every URL goes through the same guard an interactive add does — an import is the
     * one place where a file, not the operator, chooses the targets, and a subscription list pulled off the internet
     * is a list of addresses to connect to.
     *
     * <p>No discovery runs here: an exporter has already resolved each feed, and re-resolving 200 URLs would make the
     * import take minutes and fail on whichever host is down. Duplicate {@code xmlUrl} values in the file are folded
     * by the unique index, not imported twice.
     */
    @Transactional
    public ImportResult importOpml(String xml) {
        List<Parsed> parsed = Opml.parse(xml);
        if (parsed.isEmpty()) {
            throw ApiException.badRequest("no_outlines", "No outline with an xmlUrl was found in that document");
        }
        int imported = 0;
        int skipped = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (Parsed outline : parsed) {
            String url;
            try {
                url = normalise(outline.xmlUrl());
                guard.validate(url);
            } catch (RuntimeException e) {
                skipped++;
                continue;
            }
            if (!seen.add(url) || feeds.urlExists(url)) {
                skipped++;
                continue;
            }
            long id = feeds.create(url, url, blankToNull(outline.groupName()));
            // Staggered by insertion order: an import of two hundred feeds must not open two hundred sockets at once.
            queue.enqueue("fetch", FetchQueueSeeder.dedupeKey(id), id,
                Instant.now().plusSeconds(imported * 10L));
            imported++;
        }
        log.info("opml import: {} added, {} skipped of {} outlines", imported, skipped, parsed.size());
        return new ImportResult(imported, skipped);
    }

    private void require(long id) {
        existing(id);
    }

    /** The row, or a 404 that says so — the controller's read path and every write's existence check. */
    public AdminFeed existing(long id) {
        return feeds.findAdmin(id).orElseThrow(
            () -> ApiException.notFound("feed_not_found", "No subscription with id " + id));
    }

    /**
     * Accepts what an operator pastes. A bare {@code example.com/rss} is a URL the same way a browser means it, so
     * {@code https://} is added; anything that is not http(s) is refused here rather than by the guard, because the
     * guard's message is deliberately vague about internal addresses.
     */
    static String normalise(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw ApiException.badRequest("empty_url", "A feed URL is required");
        }
        if (!value.matches("(?i)^https?://.*")) {
            if (value.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) {
                throw ApiException.badRequest("scheme_not_allowed", "Only http and https subscriptions are supported");
            }
            value = "https://" + value;
        }
        // A fragment never selects a different feed and would split one subscription into two rows.
        int hash = value.indexOf('#');
        return hash < 0 ? value : value.substring(0, hash);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
