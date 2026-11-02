package com.rsshub.notify;

import com.rsshub.feedfetch.TaskQueue;
import com.rsshub.feedfetch.TaskQueue.Task;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains {@code deliver} work. Two slots, not six like the fetcher: an alert is a handful of POSTs per minute at
 * personal-subscription volumes, and a vendor that rate-limits at 20 messages/minute gains nothing from a wider
 * burst — it only gets throttled faster.
 *
 * <p>The workers are the point of this class. Boot's task scheduler is single-threaded, so a delivery loop that
 * posts inline would hold the thread for its full read timeout and stall every {@code fetch} round behind it. The
 * drain thread only claims; the send happens on a virtual thread and the permit is released by the worker, exactly
 * as {@link com.rsshub.feedfetch.FetchDispatcher} does it.
 *
 * <h2>{@code rsshub.notify.enabled=false}</h2>
 * The same reason the fetcher has one: tests drive {@link DeliveryService} directly, and a background round claiming
 * the same rows would race their assertions about task state.
 */
@Component
@ConditionalOnProperty(prefix = "rsshub.notify", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DeliverDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DeliverDispatcher.class);

    private static final int SLOTS = 2;

    /** How long past the request budget a {@code running} row stops being believed. */
    private static final Duration REAP_SLACK = Duration.ofMinutes(2);

    private final TaskQueue queue;
    private final DeliveryService delivery;
    private final NotifyPolicy policy;
    private final Semaphore slots;
    private final ExecutorService workers;
    private final String workerId;

    public DeliverDispatcher(TaskQueue queue, DeliveryService delivery, NotifyPolicy policy) {
        this.queue = queue;
        this.delivery = delivery;
        this.policy = policy;
        this.slots = new Semaphore(SLOTS, true);
        this.workers = Executors.newVirtualThreadPerTaskExecutor();
        this.workerId = "rss-hub-notify-" + ProcessHandle.current().pid();
    }

    @Scheduled(fixedDelayString = "PT10S", initialDelayString = "PT8S")
    public void drain() {
        reap();
        for (int i = 0; i < SLOTS; i++) {
            if (!slots.tryAcquire()) {
                return;
            }
            Optional<Task> claimed;
            try {
                claimed = queue.claim(DeliveryService.DELIVER_KIND, Instant.now(), workerId);
            } catch (RuntimeException e) {
                slots.release();
                throw e;
            }
            if (claimed.isEmpty()) {
                slots.release();
                return;
            }
            Task task = claimed.get();
            workers.execute(() -> {
                try {
                    delivery.run(task);
                } catch (RuntimeException e) {
                    // run() turns every expected outcome into a queue decision; this is a bug, and the task must not
                    // be retried instantly because of it.
                    log.atError().setCause(e).addKeyValue("taskId", task.id())
                        .log("deliver task threw, parking it for one minute");
                    queue.armBackoff(task.id(), Instant.now().plusSeconds(60),
                        "dispatcher:" + e.getClass().getSimpleName());
                } finally {
                    slots.release();
                }
            });
        }
    }

    private void reap() {
        Duration budget = policy.connectTimeout().plus(policy.readTimeout()).plus(REAP_SLACK);
        try {
            int reaped = queue.reapStaleRunning(DeliveryService.DELIVER_KIND, Instant.now().minus(budget),
                Instant.now().plusSeconds(30));
            if (reaped > 0) {
                log.warn("reaped {} deliver tasks stuck in running from a worker that disappeared", reaped);
            }
        } catch (RuntimeException e) {
            log.atError().setCause(e).log("stale deliver task reap failed");
        }
    }

    /** Let in-flight sends finish, then stop; rows left {@code running} are reaped on the next boot. */
    @PreDestroy
    public void shutdown() {
        workers.shutdown();
    }
}
