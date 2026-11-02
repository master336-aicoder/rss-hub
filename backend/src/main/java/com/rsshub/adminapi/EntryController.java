package com.rsshub.adminapi;

import com.rsshub.common.ApiException;
import com.rsshub.feedstore.EntryFinder;
import com.rsshub.feedstore.EntryFinder.Counters;
import com.rsshub.feedstore.EntryFinder.EntryDetail;
import com.rsshub.feedstore.EntryFinder.EntryRow;
import com.rsshub.feedstore.EntryFinder.Filter;
import com.rsshub.feedstore.EntryFinder.RevisionRow;
import com.rsshub.feedparse.NormalizedEntry.Enclosure;
import com.rsshub.rules.RuleService;
import com.rsshub.settings.SettingsService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The inbox (FR-2.4, FR-2.5). Pagination is a keyset cursor over {@code (sort_key, id)} rather than an offset, per
 * 03 §10 — with a fetch running in the background, an offset page two would slide past entries it already showed.
 */
@RestController
@RequestMapping("/api/entries")
public class EntryController {

    /** FR-2.4's tabs plus {@code dismissed}; the vocabulary is the UI's, not the column's. */
    private static final Set<String> STATES = Set.of("all", "unread", "read", "dismissed", "starred", "archived");

    private static final int MAX_LIMIT = 200;

    /**
     * FR-2.4's "只看关键词命中": the same rows that carry a rule badge, and nothing else. The badge comes from
     * {@link RuleService#alertsFor(java.util.List)}, so this filter and that mark must read the one table — a row
     * the operator can see marked must never be hidden by the filter that promises marked rows.
     */
    private static final Set<String> ALERTED_FLAGS = Set.of("true", "false");

    private final EntryFinder entries;
    private final RuleService rules;
    private final SettingsService settings;

    public EntryController(EntryFinder entries, RuleService rules, SettingsService settings) {
        this.entries = entries;
        this.rules = rules;
        this.settings = settings;
    }

    /**
     * One line in the list. No body HTML: the list page of a thousand entries must stay a small payload.
     *
     * @param alertedBy the names of the rules that already fired about this entry (FR-5.3's inbox mark), empty when
     *        none did. It lists more than one because a rule is not exclusive: two rules can both be right about the
     *        same entry, and the operator needs to see which of them spoke.
     */
    public record EntryView(long id, long feedId, String feedTitle, String groupName, String url, String title,
        String summary, List<String> authors, List<String> categories, Instant publishedAt, Instant updatedAtSrc,
        Instant fetchedAt, String readState, boolean starred, boolean archived, boolean baseline, int revision,
        List<String> alertedBy) {
    }

    public record Page(List<EntryView> items, String nextCursor, Counters counters) {
    }

    /** The reader pane: full body, the source's own metadata, and the revision history 03 §5.4 keeps. */
    public record EntryViewFull(long id, long feedId, String feedTitle, String feedSiteUrl, String url, String title,
        String summary, String contentHtml, List<AuthorView> authors, List<String> categories,
        List<EnclosureView> enclosures, Instant publishedAt, Instant updatedAtSrc, Instant fetchedAt,
        String readState, boolean starred, boolean archived, int revision, String lang, String guidSource,
        boolean stableId, List<RevisionView> revisions) {
    }

    public record AuthorView(String name, String email, String uri) {
    }

    public record EnclosureView(String url, String mimeType, Long sizeBytes, Long durationSeconds) {
    }

    public record RevisionView(int revision, String title, String contentHash, Instant updatedAtSrc,
        Instant recordedAt) {
    }

    public record StateRequest(List<Long> ids, String readState, Boolean starred, Boolean archived) {
    }

    @GetMapping
    public Page list(@RequestParam(name = "state", defaultValue = "unread") String state,
        @RequestParam(name = "feed", required = false) Long feedId,
        @RequestParam(name = "group", required = false) String group,
        @RequestParam(name = "q", required = false) String query,
        @RequestParam(name = "alerted", required = false) String alerted,
        @RequestParam(name = "category", required = false) String category,
        @RequestParam(name = "since", required = false) String since,
        @RequestParam(name = "cursor", required = false) String cursor,
        @RequestParam(name = "limit", required = false) Integer limit) {

        String wanted = state == null ? "unread" : state.toLowerCase(Locale.ROOT);
        if (!STATES.contains(wanted)) {
            throw ApiException.badRequest("bad_state", "state must be one of " + new TreeSet<>(STATES));
        }
        // A string rather than a boolean: a Spring type mismatch on a query parameter answers with its own error
        // shape, and this API's negative cases are all one code vocabulary ("bad_state" above is the precedent).
        // Absent means "do not narrow" — `alerted=false` is the complement filter, not the off switch.
        Boolean alertedOnly = null;
        if (alerted != null && !alerted.isBlank()) {
            String flag = alerted.toLowerCase(Locale.ROOT);
            if (!ALERTED_FLAGS.contains(flag)) {
                throw ApiException.badRequest("bad_alerted", "alerted must be true or false");
            }
            alertedOnly = Boolean.valueOf(flag);
        }
        // An absent limit is the settings page's number, an explicit one is this caller's: the operator tunes the
        // default without taking the escape hatch away from a slow client that asks for less.
        int requested = limit == null ? settings.intSetting(SettingsService.INBOX_PAGE_SIZE) : limit;
        int bounded = Math.min(Math.max(requested, 1), MAX_LIMIT);
        Cursor after = Cursor.decode(cursor);
        // One extra row is fetched to decide whether a cursor is worth handing back.
        Filter filter = new Filter(wanted, feedId, group, query, alertedOnly, blankToNull(category), instant(since),
            after == null ? null : after.key(), after == null ? null : after.id(), bounded + 1);

        List<EntryRow> rows = entries.list(filter);
        boolean hasMore = rows.size() > bounded;
        List<EntryRow> page = hasMore ? rows.subList(0, bounded) : rows;
        // One lookup for the whole page: the mark is a per-entry fact, but asking for it per row would make the
        // inbox's cost grow with the page size.
        Map<Long, List<String>> alerts = rules.alertsFor(page.stream().map(EntryRow::id).toList());
        List<EntryView> items = new ArrayList<>();
        page.forEach(row -> items.add(view(row, alerts.getOrDefault(row.id(), List.of()))));
        EntryRow last = page.isEmpty() ? null : page.get(page.size() - 1);
        String next = hasMore && last != null ? Cursor.encode(last.sortKey(), last.id()) : null;
        return new Page(items, next, entries.counters());
    }

    @GetMapping("/counters")
    public Counters counters() {
        return entries.counters();
    }

    @GetMapping("/{id}")
    public EntryViewFull detail(@PathVariable long id) {
        EntryDetail entry = entries.detail(id).orElseThrow(
            () -> ApiException.notFound("entry_not_found", "No entry with id " + id));
        List<RevisionView> revisions = entries.revisions(id).stream()
            .map(EntryController::revision)
            .toList();
        List<AuthorView> authors = EntryFinder.authors(entry.authorsJson()).stream()
            .map(author -> new AuthorView(author.name(), author.email(), author.uri()))
            .toList();
        List<EnclosureView> enclosures = EntryFinder.enclosures(entry.enclosuresJson()).stream()
            .map(EntryController::enclosure)
            .toList();
        String guidSource = EntryFinder.guidSource(entry.metaJson());
        return new EntryViewFull(entry.id(), entry.feedId(), entry.feedTitle(), entry.feedSiteUrl(), entry.url(),
            entry.title(), entry.summary(), entry.contentHtml(), authors,
            EntryFinder.categories(entry.categoriesJson()), enclosures, entry.publishedAt(), entry.updatedAtSrc(),
            entry.fetchedAt(), entry.readState(), entry.starred(), entry.archived(), entry.revision(), entry.lang(),
            guidSource, guidSource == null || !guidSource.equals("fallback_hash"), revisions);
    }

    /**
     * Sets any subset of the three flags. Returns the rows the statement matched — see {@code EntryFinder} for why
     * that is not the same number as "rows whose value changed", and why the UI should not treat it as one.
     */
    @PostMapping("/state")
    public Map<String, Object> state(@RequestBody StateRequest request) {
        if (request.ids() == null || request.ids().isEmpty()) {
            throw ApiException.badRequest("no_ids", "At least one entry id is required");
        }
        if (request.readState() != null && !Set.of("unread", "read", "dismissed")
            .contains(request.readState())) {
            throw ApiException.badRequest("bad_read_state", "readState must be unread, read or dismissed");
        }
        if (request.readState() == null && request.starred() == null && request.archived() == null) {
            throw ApiException.badRequest("nothing_to_set", "Send readState, starred or archived");
        }
        if (request.ids().size() > 500) {
            throw ApiException.badRequest("too_many_ids", "A single state change takes at most 500 ids");
        }
        return Map.of("applied",
            entries.applyState(request.ids(), request.readState(), request.starred(), request.archived()));
    }

    private static EntryView view(EntryRow row, List<String> alertedBy) {
        List<String> names = new ArrayList<>();
        EntryFinder.authors(row.authorsJson()).forEach(author -> {
            String name = author.name() == null || author.name().isBlank() ? author.email() : author.name();
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        });
        return new EntryView(row.id(), row.feedId(), row.feedTitle(), blankToNull(row.groupName()), row.url(),
            row.title(), row.summary(), List.copyOf(names), EntryFinder.categories(row.categoriesJson()),
            row.publishedAt(), row.updatedAtSrc(), row.fetchedAt(), row.readState(), row.starred(), row.archived(),
            row.baseline(), row.revision(), alertedBy);
    }

    private static RevisionView revision(RevisionRow row) {
        return new RevisionView(row.revision(), row.title(), row.contentHash(), row.updatedAtSrc(), row.recordedAt());
    }

    private static EnclosureView enclosure(Enclosure row) {
        return new EnclosureView(row.url(), row.mimeType(), row.sizeBytes(), row.durationSeconds());
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("bad_since", "since must be an ISO-8601 instant");
        }
    }

    /** {@code feed.group_name} is one column, so the empty string the list query uses for null is not a group name. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * {@code (sort_key, id)} as one opaque string. Opaque because the format is ours to change: the UI must not grow
     * a dependency on the encoding, and a client that tries to build its own cursor is a client that can page
     * outside the filter it started with.
     */
    record Cursor(Instant key, long id) {

        static String encode(Instant key, long id) {
            String raw = key.getEpochSecond() + ":" + key.getNano() + ":" + id;
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String raw;
            try {
                raw = new String(Base64.getUrlDecoder().decode(value),
                    StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("bad_cursor", "cursor is not one this endpoint handed out");
            }
            String[] parts = raw.split(":");
            if (parts.length != 3) {
                throw ApiException.badRequest("bad_cursor", "cursor is not one this endpoint handed out");
            }
            try {
                return new Cursor(Instant.ofEpochSecond(Long.parseLong(parts[0]), Long.parseLong(parts[1])),
                    Long.parseLong(parts[2]));
            } catch (RuntimeException e) {
                throw ApiException.badRequest("bad_cursor", "cursor is not one this endpoint handed out");
            }
        }
    }
}
