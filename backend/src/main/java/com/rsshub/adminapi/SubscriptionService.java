package com.rsshub.adminapi;

import com.rsshub.adminapi.Opml.Parsed;
import com.rsshub.common.ApiException;
import com.rsshub.common.SsrfBlockedException;
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
import java.util.ArrayList;
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

    /**
     * How many addresses one paste may yield. The cap is not about the database — it is about the request that would
     * otherwise resolve several hundred hostnames before it answered, and about an operator pasting a whole link dump
     * when they meant to subscribe to a newsletter.
     */
    private static final int MAX_BATCH_ITEMS = 200;

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
     * One line of a paste, as the splitter read it. {@code url} is null for a line that never became an address;
     * {@code reason} is the problem code an operator can paste into an issue, and is null when the line is clean.
     *
     * <p>{@code status} is one of {@code ready}, {@code duplicate}, {@code refused}, {@code invalid}, and — only
     * from the write — {@code added}. The three non-ready kinds answer three different questions, and collapsing them
     * would make a paste look rejected for the wrong reason: {@code refused} means this install's SSRF rule said no
     * (the address may be perfectly real), {@code invalid} means the text is not an address at all, and
     * {@code duplicate} means the subscription already exists, which is the one outcome that is not a mistake.
     */
    public record PlanItem(int lineNo, String label, String address, String url, String status, String reason) {

        /** Only a line the guard cleared and the list does not already hold gets written. */
        boolean writable() {
            return "ready".equals(status);
        }
    }

    /** The read-only answer to "what would this paste do", plus how many rows the write would create. */
    public record BatchPlan(List<PlanItem> items, int ready) {
    }

    /** The write's answer: the same lines, each now carrying the verdict that was actually applied. */
    public record BatchResult(int added, List<PlanItem> items) {
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
     * Reads a paste and says what each line would become, without writing anything.
     *
     * <p>It exists because a batch write cannot be undone with an undo button: the operator has to see the split
     * before committing to it — including the lines the splitter read differently than they meant. No fetch runs
     * here; the guard does resolve each host, which is the same check an interactive add does before it touches the
     * network.
     */
    public BatchPlan planBatch(String text) {
        List<PlanItem> items = classify(text);
        return new BatchPlan(items, (int) items.stream().filter(PlanItem::writable).count());
    }

    /**
     * Subscribes every {@code ready} line of a paste.
     *
     * <p>The text is re-split here rather than taken from a plan the client already saw, so the plan is a preview and
     * not an instruction: a caller cannot rename a line into an address the guard never checked, and a subscription
     * that appeared between the two calls shows up as a duplicate instead of a second row.
     *
     * <p>Like an OPML import, no discovery runs per line — an operator pasting fifty site addresses would otherwise
     * wait for fifty round trips, and one unreachable host would hold the whole request. The address is stored as
     * both requested and canonical, and if it turns out to be a page rather than a feed the first fetch says so on
     * the health column with {@code not_a_feed}; the fix is the single-address 解析 button, not this one.
     */
    @Transactional
    public BatchResult addBatch(String text, String group) {
        List<PlanItem> items = classify(text);
        List<PlanItem> written = new ArrayList<>(items.size());
        Instant now = Instant.now();
        String folder = blankToNull(group);
        int added = 0;
        for (PlanItem item : items) {
            if (!item.writable()) {
                written.add(item);
                continue;
            }
            long id = feeds.create(item.url(), item.url(), folder);
            if (item.label() != null) {
                feeds.update(id, null, null, item.label(), null);
            }
            // Staggered by position, exactly as an import is: a paste of two hundred feeds must not open two
            // hundred sockets at once, and the first one is due immediately so the panel starts filling in.
            queue.enqueue("fetch", FetchQueueSeeder.dedupeKey(id), id, now.plusSeconds(added * 10L));
            added++;
            written.add(new PlanItem(item.lineNo(), item.label(), item.address(), item.url(), "added",
                item.reason()));
        }
        if (added > 0) {
            wakeAfterCommit();
        }
        log.info("batch subscribe: {} added, {} skipped of {} lines", added, items.size() - added, items.size());
        return new BatchResult(added, written);
    }

    /**
     * The one reading of a paste, shared by the preview and the write so the two can never disagree about what a
     * line meant — only about whether the rows exist yet, which is why an address already in the list is a
     * {@code duplicate} here and an {@code already_subscribed} conflict for a single add.
     */
    private List<PlanItem> classify(String text) {
        List<PastedSubscriptions.Candidate> candidates = PastedSubscriptions.split(text);
        if (candidates.isEmpty()) {
            throw ApiException.badRequest("nothing_to_add", "No address found in that text");
        }
        if (candidates.size() > MAX_BATCH_ITEMS) {
            throw ApiException.badRequest("too_many_lines",
                "A paste is read as at most " + MAX_BATCH_ITEMS + " addresses; split it into smaller batches");
        }
        List<PlanItem> items = new ArrayList<>(candidates.size());
        Set<String> seen = new LinkedHashSet<>();
        for (PastedSubscriptions.Candidate candidate : candidates) {
            String label = blankToNull(candidate.label());
            if (label != null && label.length() > MAX_TITLE_LENGTH) {
                items.add(new PlanItem(candidate.lineNo(), label.substring(0, MAX_TITLE_LENGTH),
                    candidate.address(), null, "invalid",
                    "the title alone is longer than " + MAX_TITLE_LENGTH + " characters"));
                continue;
            }
            String url;
            try {
                url = normalise(candidate.address());
            } catch (ApiException e) {
                items.add(new PlanItem(candidate.lineNo(), label, candidate.address(), null, "invalid", e.code()));
                continue;
            }
            try {
                guard.validate(url);
            } catch (SsrfBlockedException e) {
                items.add(new PlanItem(candidate.lineNo(), label, candidate.address(), url, "refused", e.reason()));
                continue;
            } catch (RuntimeException e) {
                items.add(new PlanItem(candidate.lineNo(), label, candidate.address(), url, "invalid", "malformed_url"));
                continue;
            }
            String status = "ready";
            String reason = null;
            if (!seen.add(url)) {
                status = "duplicate";
                reason = "already_in_paste";
            } else if (feeds.urlExists(url)) {
                status = "duplicate";
                reason = "already_subscribed";
            }
            items.add(new PlanItem(candidate.lineNo(), label, candidate.address(), url, status, reason));
        }
        return items;
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

    /** Puts this feed's work at the front of the queue and asks for a round now rather than at the next tick. */
    private void armNow(long feedId) {
        queue.armNow("fetch", FetchQueueSeeder.dedupeKey(feedId), feedId, Instant.now());
        wakeAfterCommit();
    }

    /**
     * Asks the dispatcher for a round as soon as this transaction lands. The wake is deferred because the queue rows
     * written moments ago are not yet visible to the dispatcher's own connection: waking inside the transaction would
     * scan a queue that still looks empty, and the feed would sit until the fifteen-second round anyway — which is the
     * delay this exists to remove.
     */
    private void wakeAfterCommit() {
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
