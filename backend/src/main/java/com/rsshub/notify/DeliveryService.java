package com.rsshub.notify;

import com.rsshub.common.ApiException;
import com.rsshub.feedfetch.TaskQueue;
import com.rsshub.feedfetch.TaskQueue.Task;
import com.rsshub.rules.NotificationRepository;
import com.rsshub.rules.RuleRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 03 §8.1's second half: the hit is recorded in the ingest transaction, and the send happens here, in its own
 * transaction-free round, driven by a {@code deliver} task. Nothing in this class runs inside a caller's
 * transaction — a webhook that hangs for ten seconds must not hold an entry ingest open with it.
 *
 * <h2>Why the payload is the source of truth</h2>
 * The envelope frozen in {@code scheduled_task.payload} at hit time is parsed rather than rebuilt from
 * {@code notification_log}: that table keeps no entry text and its {@code rule_id} goes null when the rule is
 * deleted, so a replay after an edit would otherwise send a different message than the one that was decided on —
 * or nothing at all.
 *
 * <h2>The ladder (03 §8.3)</h2>
 * Five attempts, spaced 1/5/15/60 minutes, then the row lands in {@code failed} and only the operator can put it
 * back. {@code scheduled_task.attempts} is the counter because the claim increments it before this class sees the
 * task; {@code notification_log.attempts} mirrors it for the UI.
 *
 * <h2>The breaker (03 §8.2)</h2>
 * A rule whose last five delivery <em>attempts</em> all failed is switched off with a reason — which in practice is
 * one entry whose ladder ran out. The check reads the log rather than a counter in this process so it survives a
 * restart, and so a rule with two channels fails as one rule, not as two half-counted ones.
 */
@Service
public class DeliveryService {

    static final String DELIVER_KIND = "deliver";

    /** Waits after attempts 1..4; the fifth failure is terminal. */
    private static final Duration[] BACKOFF = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
        Duration.ofMinutes(60)};

    /** 03 §8.2's consecutive-failure count. */
    private static final int BREAKER_FAILURES = 5;

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    private final TaskQueue queue;
    private final NotificationRepository notifications;
    private final RuleRepository rules;
    private final NotifyPolicy policy;
    private final Map<String, NotifyChannel> channels;

    public DeliveryService(TaskQueue queue, NotificationRepository notifications, RuleRepository rules,
        NotifyPolicy policy, List<NotifyChannel> adapters) {
        this.queue = queue;
        this.notifications = notifications;
        this.rules = rules;
        this.policy = policy;
        Map<String, NotifyChannel> byName = new HashMap<>();
        adapters.forEach(adapter -> byName.put(adapter.name(), adapter));
        this.channels = Map.copyOf(byName);
    }

    /**
     * One attempt at one task. Every outcome ends in a queue decision; nothing here rethrows, because the caller
     * owns a worker thread and a task that throws would otherwise be retried instantly in a loop.
     */
    public void run(Task task) {
        Notification envelope = Notification.parse(task.payload());
        Optional<NotificationRepository.Row> found = notifications.find(envelope.notificationId());
        if (found.isEmpty()) {
            queue.finish(task.id(), "failed", "notification " + envelope.notificationId() + " is gone");
            return;
        }
        if (!NotificationRepository.PENDING.equals(found.get().state())) {
            // Only reachable through a race (a replay while a reaped task was still in flight). Sending twice to a
            // vendor is worse than dropping the second round, and the row already carries the first verdict.
            queue.finish(task.id(), "success", "already " + found.get().state());
            return;
        }
        NotifyChannel channel = channels.get(envelope.channel());
        if (channel == null) {
            terminate(task, envelope, Result.transport("no such channel: " + envelope.channel()));
            return;
        }
        Result result;
        try {
            result = channel.send(envelope.withSecret(policy.secret(envelope.secretRef())));
        } catch (RuntimeException e) {
            // An adapter is not allowed to throw; if one does, the ladder still has to bound it.
            log.atError().setCause(e).addKeyValue("notificationId", envelope.notificationId())
                .log("channel adapter threw");
            result = Result.transport(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (result.delivered()) {
            notifications.markSent(envelope.notificationId(), result.httpStatus());
            queue.finish(task.id(), "success", null);
            log.atInfo().addKeyValue("notificationId", envelope.notificationId())
                .addKeyValue("channel", envelope.channel()).log("alert delivered");
            return;
        }
        if (task.attempts() >= task.maxAttempts()) {
            terminate(task, envelope, result);
            return;
        }
        notifications.noteAttempt(envelope.notificationId(), result.httpStatus(), result.failure());
        Duration wait = BACKOFF[Math.min(task.attempts() - 1, BACKOFF.length - 1)];
        queue.armBackoff(task.id(), Instant.now().plus(wait), result.failure());
    }

    /**
     * Puts a notification back on the wire with the body that was decided on (03 §8.3's manual replay). Only a
     * {@code failed} row is replayable: a suppressed or quiet hit has no envelope to send, because it never queued
     * one, and re-sending a delivered alert is not what the button means.
     */
    public NotificationRepository.Row replay(long notificationId) {
        NotificationRepository.Row row = notifications.find(notificationId)
            .orElseThrow(() -> ApiException.notFound("unknown_notification",
                "No notification " + notificationId));
        if (!NotificationRepository.FAILED.equals(row.state())) {
            throw ApiException.badRequest("not_replayable",
                "Only a failed notification can be replayed; this one is " + row.state());
        }
        Task task = queue.findByDedupeKey(DELIVER_KIND, "deliver:" + notificationId)
            .orElseThrow(() -> ApiException.badRequest("no_payload",
                "This notification has no stored alert body to resend"));
        notifications.resetToPending(notificationId);
        queue.armWithPayload(task.id(), Instant.now(), task.payload());
        return notifications.find(notificationId)
            .orElseThrow(() -> ApiException.internal("notification_lost",
                "Notification " + notificationId + " disappeared during replay"));
    }

    /** The terminal failure: log it, park the task, and let the breaker decide whether the rule keeps its voice. */
    private void terminate(Task task, Notification envelope, Result last) {
        String reason = last.failure();
        notifications.markFailed(envelope.notificationId(), last.httpStatus(), reason);
        queue.finish(task.id(), "failed", reason);
        tripBreaker(envelope, task, reason);
    }

    private void tripBreaker(Notification envelope, Task task, String reason) {
        if (envelope.ruleId() <= 0) {
            return;
        }
        // Look back over the same number of rows the threshold needs: a run longer than five attempts cannot start
        // inside them, and a success anywhere in the walk stops it.
        if (notifications.failedAttemptRun(envelope.ruleId(), BREAKER_FAILURES) < BREAKER_FAILURES) {
            return;
        }
        rules.disableByBreaker(envelope.ruleId(), "task " + task.id() + ": " + reason);
        log.atWarn().addKeyValue("ruleId", envelope.ruleId())
            .log("rule disabled by the delivery circuit breaker: {}", reason);
    }
}
