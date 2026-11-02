package com.rsshub.rules;

import com.rsshub.common.ApiException;
import com.rsshub.feedstore.EntryFinder;
import com.rsshub.feedstore.EntryNewEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * The operator's side of {@code rule}: validate on the way in, judge on the way out (03 §10's {@code /api/rules}).
 *
 * <h2>Why validation is on the save path</h2>
 * A rule is judged later, by a listener that cannot report a problem back to anyone. Every mistake that can be
 * caught here — a keyword list that is not an array, a regex that does not compile, a channel with no adapter, a
 * quiet window with no time zone — becomes a 400 naming the field instead of a rule that quietly never fires.
 */
@Service
public class RuleService {

    /** FR-5.4's sample size, and the ceiling on a preview request. */
    private static final int PREVIEW_MAX = 200;

    private final RuleRepository rules;
    private final NotificationRepository notifications;
    private final RuleMatcher matcher;
    private final EntryFinder entries;

    public RuleService(RuleRepository rules, NotificationRepository notifications, RuleMatcher matcher,
        EntryFinder entries) {
        this.rules = rules;
        this.notifications = notifications;
        this.matcher = matcher;
        this.entries = entries;
    }

    /** The columns as the UI sees them: the four JSON ones decoded, so the SPA never parses a string field. */
    public record View(long id, String name, boolean enabled, JsonNode match, JsonNode actions, JsonNode suppression,
        JsonNode quietHours, int hitCount, String disabledReason, Instant createdAt, Instant updatedAt) {
    }

    /**
     * @param name required on create; on update an absent value keeps the stored name
     * @param enabled on create it defaults to true; on update an absent value keeps the stored one, because the UI's
     *     "save" button is not a decision to re-enable a rule the breaker stopped
     */
    public record Request(String name, JsonNode match, JsonNode actions, JsonNode suppression, JsonNode quietHours,
        Boolean enabled) {
    }

    /** One entry a rule would have hit, and the words that made it hit (FR-5.4). */
    public record Hit(long entryId, String title, String feedTitle, Instant publishedAt, List<String> keywords) {
    }

    public record Preview(int scanned, int matched, List<Hit> hits) {
    }

    public List<View> list() {
        return rules.list().stream().map(RuleService::view).toList();
    }

    public View get(long id) {
        return view(rules.find(id).orElseThrow(() -> ApiException.notFound("unknown_rule", "No rule " + id)));
    }

    @Transactional
    public View create(Request request) {
        Draft draft = draft(request, null);
        long id = rules.insert(draft.name(), draft.match(), draft.actions(), draft.suppression(),
            draft.quietHours());
        if (Boolean.FALSE.equals(request.enabled())) {
            rules.setEnabled(id, false, null);
        }
        return get(id);
    }

    @Transactional
    public View update(long id, Request request) {
        RuleRepository.StoredRule stored = require(id);
        Draft draft = draft(request, stored);
        rules.update(id, draft.name(), draft.match(), draft.actions(), draft.suppression(), draft.quietHours());
        if (request.enabled() != null && request.enabled() != stored.enabled()) {
            rules.setEnabled(id, request.enabled(), request.enabled() ? null : stored.disabledReason());
        }
        return get(id);
    }

    @Transactional
    public View setEnabled(long id, boolean enabled) {
        RuleRepository.StoredRule stored = require(id);
        if (enabled) {
            // Re-arming is the operator's decision, and the only way to make it is to say so explicitly: an edit
            // that happens to send enabled=true is the one the breaker must not be bypassed by.
            rules.setEnabled(id, true, null);
        } else {
            rules.setEnabled(id, false, stored.disabledReason());
        }
        return get(id);
    }

    @Transactional
    public void delete(long id) {
        rules.delete(id);
    }

    /** How many rules the breaker stopped: 03 §8.2's red hint on the health page. */
    public int autoDisabledCount() {
        return rules.countAutoDisabled();
    }

    /** FR-5.3's inbox mark, resolved for one page of entries. */
    public java.util.Map<Long, List<String>> alertsFor(List<Long> entryIds) {
        return notifications.ruleNamesByEntries(entryIds);
    }

    /**
     * FR-5.4: judge the most recent entries and report what would have been sent, without recording or delivering
     * anything. The sample is the inbox's own ordering, so what the preview shows is what the operator has been
     * reading — a preview over "the newest 200 by id" would quietly disagree with the list on screen.
     */
    public Preview preview(long id, int limit) {
        RuleRepository.StoredRule stored = require(id);
        MatchSpec spec = MatchSpec.parse(stored.matchJson());
        int scanned = Math.min(Math.max(limit, 1), PREVIEW_MAX);
        Instant now = Instant.now();
        List<EntryFinder.EntryRow> sample = entries.list(new EntryFinder.Filter("all", null, null, null, null, null,
            null, null, null, scanned));
        List<Hit> hits = new ArrayList<>();
        for (EntryFinder.EntryRow row : sample) {
            RuleMatcher.Outcome outcome = matcher.match(spec, event(row), now);
            if (outcome.matched()) {
                hits.add(new Hit(row.id(), row.title(), row.feedTitle(), row.publishedAt(), outcome.keywords()));
            }
        }
        return new Preview(sample.size(), hits.size(), List.copyOf(hits));
    }

    /** A stored row, re-judged as if it were new: the preview must not need the ingest path. */
    private static EntryNewEvent event(EntryFinder.EntryRow row) {
        return new EntryNewEvent(row.id(), row.feedId(), row.feedTitle(), row.groupName(), row.url(), row.title(),
            row.summary(), null, EntryFinder.categories(row.categoriesJson()), row.publishedAt(), row.fetchedAt());
    }

    private RuleRepository.StoredRule require(long id) {
        return rules.find(id).orElseThrow(() -> ApiException.notFound("unknown_rule", "No rule " + id));
    }

    private Draft draft(Request request, RuleRepository.StoredRule stored) {
        // An absent name keeps the stored one, exactly as the JSON columns do: the editor's save always sends it, and
        // a PATCH that changes only the suppression window is not a decision to rename the rule.
        String name = request.name() == null ? (stored == null ? null : stored.name()) : request.name().trim();
        if (name == null || name.isEmpty()) {
            throw ApiException.badRequest("name_required", "A rule needs a name");
        }
        if (name.length() > 255) {
            throw ApiException.badRequest("name_too_long", "A rule name is capped at 255 characters");
        }
        if (rules.nameTaken(name, stored == null ? -1 : stored.id())) {
            throw ApiException.conflict("rule_exists", "A rule named " + name + " already exists");
        }
        String match = text(request.match(), stored == null ? null : stored.matchJson(), "match", true);
        String actions = text(request.actions(), stored == null ? null : stored.actionsJson(), "actions", true);
        String suppression = text(request.suppression(), stored == null ? null : stored.suppressionJson(),
            "suppression", false);
        String quietHours = quietHours(request.quietHours(), stored);
        MatchSpec.parse(match).validate();
        Action.parseAll(actions);
        Suppression.parse(suppression == null ? "{}" : suppression);
        if (quietHours != null) {
            QuietHours.parse(quietHours);
        }
        return new Draft(name, match, actions, suppression, quietHours);
    }

    /**
     * An absent JSON column on a PATCH keeps the stored value, exactly as the event-stream editor does. The two
     * exceptions are {@code suppression} (whose default is a real policy, not a hole) and a required column on a
     * create, which has nothing to keep. {@code quietHours} adds a third case and is handled by
     * {@link #quietHours(JsonNode, RuleRepository.StoredRule)}.
     */
    private static String text(JsonNode node, String storedJson, String field, boolean required) {
        if (node == null || node.isNull()) {
            if (required && storedJson == null) {
                throw ApiException.badRequest(field + "_required", "A rule needs a " + field);
            }
            return storedJson;
        }
        return node.toString();
    }

    /**
     * {@code quiet_hours} is the one column the editor can genuinely switch off, so it needs a third state beyond
     * "sent" and "absent": an empty object means "no window", while an absent field still keeps what is stored.
     * A partial object stays a 400 — a typo is not a way to turn the window off.
     */
    private static String quietHours(JsonNode node, RuleRepository.StoredRule stored) {
        if (node != null && node.isObject() && node.size() == 0) {
            return null;
        }
        return text(node, stored == null ? null : stored.quietHoursJson(), "quietHours", false);
    }

    private record Draft(String name, String match, String actions, String suppression, String quietHours) {
    }

    private static View view(RuleRepository.StoredRule rule) {
        return new View(rule.id(), rule.name(), rule.enabled(), node(rule.matchJson()), node(rule.actionsJson()),
            node(rule.suppressionJson()), node(rule.quietHoursJson()), rule.hitCount(), rule.disabledReason(),
            rule.createdAt(), rule.updatedAt());
    }

    private static JsonNode node(String json) {
        return json == null || json.isBlank() ? null : RuleJson.MAPPER.readTree(json);
    }
}
