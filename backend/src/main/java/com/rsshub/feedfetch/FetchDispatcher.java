package com.rsshub.feedfetch;

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
 * 03 §4.2: a 15-second round that claims what is due and hands it to a worker. The queue is the schedule, so this
 * class has no cron knowledge at all — it only asks "what is due now" and "may I run another one".
 *
 * <h2>Two locks that must not be confused</h2>
 * The semaphore is the global fetch concurrency from 03 §4.1 (six exchanges in flight, whatever the feed count).
 * {@link HostPoliteness} is the per-host rule. A permit is taken before claiming so a task never sits claimed and
 * {@code running} while waiting for capacity; it is released by the worker, so a feed that hangs for its full read
 * budget occupies exactly one slot and no more.
 *
 * <h2>Reaping</h2>
 * A task claimed by a process that then died stays {@code running} forever, which would silently stop a
 * subscription. Each round first frees anything claimed longer ago than the full request budget plus slack.
 *
 * <h2>{@link #wake()}</h2>
 * The round is the schedule, but 15 seconds is an eternity for a button that says "fetch now". A wake runs the same
 * drain on its own thread: {@link TaskQueue#claim} is a conditional update, so two drains in the same instant cannot
 * claim one row twice, and the semaphore still decides how much leaves the process. The request thread never blocks.
 *
 * <h2>{@code rsshub.fetch.enabled=false}</h2>
 * The scheduler is the only thing that starts fetching by itself. Tests drive {@link FeedFetchService} directly,
 * and a background round claiming the same rows would race every assertion about task state.
 */
@Component
@ConditionalOnProperty(prefix = "rsshub.fetch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FetchDispatcher implements FetchWakeup {

    private static final Logger log = LoggerFactory.getLogger(FetchDispatcher.class);

    /** How long past the request budget a {@code running} row stops being believed. */
    private static final Duration REAP_SLACK = Duration.ofMinutes(2);

    private final TaskQueue queue;
    private final FeedFetchService service;
    private final FetchPolicy policy;
    private final Semaphore slots;
    private final ExecutorService workers;
    private final ExecutorService wakeRound;
    private final String workerId;

    public FetchDispatcher(TaskQueue queue, FeedFetchService service, FetchPolicy policy) {
        this.queue = queue;
        this.service = service;
        this.policy = policy;
        this.slots = new Semaphore(policy.concurrency(), true);
        // One virtual thread per exchange. A platform pool sized below the semaphore would queue work behind
        // threads that already own a permit; with virtual threads the semaphore is the only limit.
        this.workers = Executors.newVirtualThreadPerTaskExecutor();
        // One thread, and one round at a time: ten clicks should not start ten scans of the same small table.
        this.wakeRound = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("fetch-wake-", 0).factory());
        this.workerId = "rss-hub-" + ProcessHandle.current().pid();
    }

    @Scheduled(fixedDelayString = "${rsshub.fetch.round:PT15S}",
        initialDelayString = "${rsshub.fetch.round-initial-delay:PT5S}")
    public void drain() {
        reap();
        for (int i = 0; i < policy.concurrency(); i++) {
            if (!slots.tryAcquire()) {
                return;
            }
            Optional<Task> claimed;
            try {
                claimed = queue.claim("fetch", Instant.now(), workerId);
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
                    service.run(task);
                } catch (RuntimeException e) {
                    // run() already converts every expected outcome into a queue decision; this is a bug.
                    log.atError().setCause(e).addKeyValue("taskId", task.id())
                        .log("fetch task threw, parking it for one minute");
                    queue.armBackoff(task.id(), Instant.now().plusSeconds(60), "dispatcher:" + e.getClass().getSimpleName());
                } finally {
                    slots.release();
                }
            });
        }
    }

    /**
     * Runs one round now, on its own thread, so the request that armed work does not wait for the scheduler's next
     * tick. Failure is logged and dropped: this is an acceleration of a round that would have run anyway, and the
     * only thing a thrown exception could tell anyone is that the queue will be scanned again in fifteen seconds.
     */
    @Override
    public void wake() {
        wakeRound.execute(() -> {
            try {
                drain();
            } catch (RuntimeException e) {
                log.atWarn().setCause(e).log("woken fetch round failed; the scheduled round will retry");
            }
        });
    }

    private void reap() {
        Duration budget = policy.connectTimeout().plus(policy.readTimeout()).plus(REAP_SLACK);
        try {
            int reaped = queue.reapStaleRunning("fetch", Instant.now().minus(budget), Instant.now().plusSeconds(30));
            if (reaped > 0) {
                log.warn("reaped {} fetch tasks stuck in running from a worker that disappeared", reaped);
            }
        } catch (RuntimeException e) {
            log.atError().setCause(e).log("stale task reap failed");
        }
    }

    /** Let in-flight exchanges finish, then stop; rows left {@code running} are reaped on the next boot. */
    @PreDestroy
    public void shutdown() {
        wakeRound.shutdown();
        workers.shutdown();
    }
}
