package com.rsshub.adminapi;

import com.rsshub.feedfetch.TaskQueue;
import com.rsshub.feedstore.EntryFinder;
import com.rsshub.feedstore.FeedRepository;
import com.rsshub.notify.NotifyPolicy;
import com.rsshub.rules.NotificationRepository;
import com.rsshub.rules.RuleRepository;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FR-9's status half: system, JVM and service facts the settings page shows alongside the knobs. Everything comes
 * from the JDK's own MXBeans and plain SQL counts — never from the actuator surface, whose endpoints (env,
 * heapdump) are exactly what 03 §11 says a single-operator install must not expose, and which this application does
 * not publish in the first place.
 *
 * <p>The one deliberate exception to "read-only is free": the counts live inside the connection try. A database
 * that cannot be reached turns them into zeros and {@code up} into false rather than into a 500, because a status
 * page whose failure mode is "unavailable" cannot report the failure it exists to report.
 */
@RestController
@RequestMapping("/api/system")
public class SystemStatusController {

    private final DataSource dataSource;
    private final Environment environment;
    private final FeedRepository feeds;
    private final EntryFinder entries;
    private final RuleRepository rules;
    private final NotificationRepository notifications;
    private final TaskQueue tasks;
    private final NotifyPolicy notifyPolicy;

    public SystemStatusController(DataSource dataSource, Environment environment, FeedRepository feeds,
        EntryFinder entries, RuleRepository rules, NotificationRepository notifications, TaskQueue tasks,
        NotifyPolicy notifyPolicy) {
        this.dataSource = dataSource;
        this.environment = environment;
        this.feeds = feeds;
        this.entries = entries;
        this.rules = rules;
        this.notifications = notifications;
        this.tasks = tasks;
        this.notifyPolicy = notifyPolicy;
    }

    public record StatusView(Instant now, SystemInfo system, JvmInfo jvm, DatabaseInfo database,
        ServicesView services) {
    }

    public record SystemInfo(String osName, String osVersion, String osArch, String javaVersion, String javaVendor,
        Instant jvmStartedAt, long uptimeSeconds) {
    }

    public record JvmInfo(long heapUsedBytes, long heapCommittedBytes, long heapMaxBytes, int liveThreads,
        long loadedClasses) {
    }

    public record DatabaseInfo(boolean up, String productName, String productVersion, long feeds, long entries,
        long rules, long notifications) {
    }

    /**
     * @param fetchEnabled whether the fetch dispatcher is scheduled in this process; mirroring the exact
     *        {@code @ConditionalOnProperty} the dispatcher itself uses, including the "missing means on" default
     * @param taskQueueByState the queue's rows by state, fetch and deliver together — the two kinds share the table
     *        and the question the page asks is "what is stuck", not "whose row is it"
     * @param notificationsByState the delivery log's states; {@code pending} above zero with deliveries enabled is
     *        the visible symptom of an outbound problem
     */
    public record ServicesView(boolean fetchEnabled, boolean notifyEnabled, Map<String, Long> taskQueueByState,
        Map<String, Long> notificationsByState, Instant lastFetchedAt) {
    }

    @GetMapping("/status")
    public StatusView status() {
        var runtime = ManagementFactory.getRuntimeMXBean();
        SystemInfo system = new SystemInfo(System.getProperty("os.name"), System.getProperty("os.version"),
            System.getProperty("os.arch"), System.getProperty("java.version"), System.getProperty("java.vendor"),
            Instant.ofEpochMilli(runtime.getStartTime()), runtime.getUptime() / 1000);

        var memory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        JvmInfo jvm = new JvmInfo(memory.getUsed(), memory.getCommitted(), memory.getMax(),
            ManagementFactory.getThreadMXBean().getThreadCount(),
            ManagementFactory.getClassLoadingMXBean().getLoadedClassCount());

        DatabaseInfo database;
        Map<String, Long> taskQueue = new LinkedHashMap<>();
        Map<String, Long> notificationStates = new LinkedHashMap<>();
        Instant lastFetchedAt = null;
        long feedCount = 0;
        long entryCount = 0;
        long ruleCount = 0;
        long notificationCount = 0;
        boolean up = false;
        String productName = null;
        String productVersion = null;
        try (Connection connection = dataSource.getConnection()) {
            var metadata = connection.getMetaData();
            productName = metadata.getDatabaseProductName();
            productVersion = metadata.getDatabaseProductVersion();
            up = true;
            feedCount = feeds.count();
            entryCount = entries.counters().total();
            ruleCount = rules.count();
            tasks.countsByState().forEach(taskQueue::put);
            notifications.countsByState().forEach(notificationStates::put);
            for (long count : notificationStates.values()) {
                notificationCount += count;
            }
            lastFetchedAt = entries.lastFetchedAt().orElse(null);
        } catch (SQLException | DataAccessException e) {
            // up stays false and every count stays zero; the row's job is to say so, not to throw.
        }
        database = new DatabaseInfo(up, productName, productVersion, feedCount, entryCount, ruleCount,
            notificationCount);

        ServicesView services = new ServicesView(
            environment.getProperty("rsshub.fetch.enabled", Boolean.class, true), notifyPolicy.enabled(),
            taskQueue, notificationStates, lastFetchedAt);
        return new StatusView(Instant.now(), system, jvm, database, services);
    }
}
