package com.rsshub.adminapi;

import com.rsshub.feedfetch.FeedDiscoverer.Discovery;
import com.rsshub.feedfetch.FetchPolicy;
import com.rsshub.feedstore.EntryFinder;
import com.rsshub.feedstore.FeedRepository;
import com.rsshub.feedstore.FeedRepository.AdminFeed;
import com.rsshub.feedstore.FeedRepository.Subscription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Subscription management (FR-2.1, FR-2.2's manual trigger). The DTOs the fetcher writes and the operator edits are
 * the same table, so this returns {@link AdminFeed} rows directly — a view record that only renames fields would be
 * a second contract to keep in sync for no gain at this size.
 */
@RestController
@RequestMapping("/api/feeds")
public class FeedController {

    private final FeedRepository feeds;
    private final SubscriptionService subscriptions;
    private final EntryFinder entries;
    private final FetchPolicy fetchPolicy;

    public FeedController(FeedRepository feeds, SubscriptionService subscriptions, EntryFinder entries,
        FetchPolicy fetchPolicy) {
        this.feeds = feeds;
        this.subscriptions = subscriptions;
        this.entries = entries;
        this.fetchPolicy = fetchPolicy;
    }

    /** What the operator pasted. {@code group} is optional; an empty string means "move to no group". */
    public record AddRequest(@NotBlank String url, String group) {
    }

    /** The cadence the operator is editing against, so the page can say "default" with this install's number. */
    public record PolicyView(long defaultIntervalMinutes, long minIntervalMinutes) {
    }

    @GetMapping
    public List<AdminFeed> list() {
        return feeds.listAdmin();
    }

    @GetMapping("/policy")
    public PolicyView policy() {
        return new PolicyView(fetchPolicy.defaultIntervalMinutes(), fetchPolicy.minIntervalMinutes());
    }

    @GetMapping("/{id}")
    public AdminFeed get(@PathVariable long id) {
        return subscriptions.existing(id);
    }

    /**
     * Resolves an address without committing to it, so the UI can show what it found — or the several feeds a page
     * offers — before the operator adds one. Separate from the add because discovery is a network round trip and a
     * failed one must not look like a failed write.
     */
    @PostMapping("/discover")
    public Discovery discover(@Valid @RequestBody AddRequest request) {
        return subscriptions.discover(request.url());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminFeed add(@Valid @RequestBody AddRequest request) {
        return subscriptions.add(request.url(), request.group());
    }

    /**
     * The patch a subscription accepts, read as the raw map so the service can tell an absent key from a null one and
     * refuse a key that is not editable. The four names are documented on
     * {@link SubscriptionService#update(long, java.util.Map)}: {@code title}, {@code group}, {@code state},
     * {@code intervalMinutes} — and deliberately no address, which the entries and the queue row are attached to.
     */
    @PatchMapping("/{id}")
    public AdminFeed update(@PathVariable long id, @RequestBody Map<String, Object> patch) {
        subscriptions.update(id, patch);
        return subscriptions.existing(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        subscriptions.delete(id);
    }

    /**
     * 03 §10: moves {@code fire_at} forward instead of creating a second row, which is why two clicks cannot stack
     * up work, and then asks the dispatcher for a round now rather than waiting up to fifteen seconds for the next
     * one. Still 202: the exchange runs on the scheduler's worker, so this returns as soon as the queue has said yes.
     */
    @PostMapping("/{id}/refresh")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> refresh(@PathVariable long id) {
        subscriptions.refresh(id);
        return Map.of("queued", true, "id", id);
    }

    /** "Mark this feed read" — the one bulk action the inbox needs that is not bounded by an id list. */
    @PostMapping("/{id}/read")
    public Map<String, Object> markRead(@PathVariable long id) {
        subscriptions.existing(id);
        return Map.of("marked", entries.markFeedRead(id));
    }

    /**
     * The group names for the inbox's group picker. Distinct, because three feeds in one folder is one folder and
     * not three buttons, and blank is not a folder either.
     */
    @GetMapping("/groups")
    public List<String> groups() {
        return feeds.listSubscriptions().stream()
            .map(Subscription::groupName)
            .filter(name -> name != null && !name.isBlank())
            .distinct()
            .sorted()
            .toList();
    }
}
