package com.rsshub.rules;

import com.rsshub.feedfetch.TaskQueue;
import com.rsshub.feedstore.EntryNewEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 03 §8.1's executor: for every new entry, judge every enabled rule and record what was decided. It runs inside the
 * ingest transaction (the event is published there), so the hit rows commit or roll back with the entries — the
 * exactly-one-of-the-two-halves the design asks for, and the reason nothing here talks to a network.
 *
 * <h2>One rule cannot break a fetch</h2>
 * Each rule is judged in its own {@code try/catch}. A rule row edited by hand into invalid JSON, or a keyword list
 * that throws, costs that rule its alerts and nothing else: losing the subscription's new entries because an alert
 * misbehaved would be the wrong trade every time.
 */
@Component
public class RuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RuleEvaluator.class);

    /** 03 §8.1: delivery is a separate unit of work; this is only its queue entry. */
    static final String DELIVER_KIND = "deliver";

    private final RuleRepository rules;
    private final NotificationRepository notifications;
    private final RuleMatcher matcher;
    private final TaskQueue queue;

    public RuleEvaluator(RuleRepository rules, NotificationRepository notifications, RuleMatcher matcher,
        TaskQueue queue) {
        this.rules = rules;
        this.notifications = notifications;
        this.matcher = matcher;
        this.queue = queue;
    }

    @EventListener
    public void onEntryNew(EntryNewEvent entry) {
        Instant now = Instant.now();
        for (RuleRepository.StoredRule rule : rules.listEnabled()) {
            try {
                evaluate(rule, entry, now);
            } catch (RuntimeException e) {
                log.atWarn().setCause(e).addKeyValue("ruleId", rule.id())
                    .log("rule evaluation failed, entry ingest continues");
            }
        }
    }

    private void evaluate(RuleRepository.StoredRule rule, EntryNewEvent entry, Instant now) {
        RuleMatcher.Outcome outcome = matcher.match(MatchSpec.parse(rule.matchJson()), entry, now);
        if (!outcome.matched()) {
            return;
        }
        rules.bumpHits(rule.id(), 1);

        List<Action> actions = Action.parseAll(rule.actionsJson());
        Suppression suppression = Suppression.parse(rule.suppressionJson());
        QuietHours quiet = rule.quietHoursJson() == null || rule.quietHoursJson().isBlank()
            ? null
            : QuietHours.parse(rule.quietHoursJson());
        String dedupeKey = suppression.dedupeKey(rule.id(), entry);
        String state = decide(dedupeKey, suppression, quiet, now);

        for (Action action : actions) {
            long notificationId = notifications.record(state, rule.id(), entry.entryId(), action.channel(), dedupeKey);
            if (NotificationRepository.PENDING.equals(state)) {
                queue.enqueue(DELIVER_KIND, "deliver:" + notificationId, notificationId, now,
                    AlertPayload.render(notificationId, rule, action, entry, outcome.keywords()));
            }
        }
        log.debug("rule {} hit entry {} as {} on {} channel(s)", rule.id(), entry.entryId(), state, actions.size());
    }

    /**
     * 03 §8.2's two reasons not to speak, checked in the order the design lists them. Both still write a row: a hit
     * nobody can see afterwards is indistinguishable from a rule that never matched, and FR-5.3's audit trail is the
     * only place the difference lives.
     */
    private String decide(String dedupeKey, Suppression suppression, QuietHours quiet, Instant now) {
        if (suppression.enabled()
            && notifications.hasRecentSent(dedupeKey, now.minus(Duration.ofHours(suppression.hours())))) {
            return NotificationRepository.SUPPRESSED;
        }
        if (quiet != null && quiet.quietAt(now)) {
            return NotificationRepository.SKIPPED_QUIET;
        }
        return NotificationRepository.PENDING;
    }
}
