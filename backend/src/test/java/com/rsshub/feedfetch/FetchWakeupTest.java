package com.rsshub.feedfetch;

import static com.rsshub.feedfetch.LocalFeedServer.RSS_WITH_ETAG;
import static com.rsshub.feedfetch.LocalFeedServer.Scenario.ok;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The one promise the subscription buttons make that the queue alone cannot keep: after a wake, the work has
 * actually run, not merely been scheduled. Every other test in this package runs with the dispatcher switched off,
 * which proves a row gets armed but says nothing about when it fires — and "刷新立即执行" is a promise about when.
 *
 * <p>So this class is the mirror image: the scheduler is on, and its round is pushed out to an hour. Nothing can
 * fetch this feed except {@link FetchWakeup#wake()}, which is the only thing the API calls.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:fetchwakeup;DB_CLOSE_DELAY=-1",
    "rsshub.operator.username=admin",
    "rsshub.operator.password=a-test-only-password",
    "rsshub.fetch.enabled=true",
    "rsshub.fetch.round=PT1H",
    "rsshub.fetch.round-initial-delay=PT1H",
    "rsshub.fetch.interval=60m",
    "rsshub.fetch.jitter=0s",
    "rsshub.fetch.connect-timeout=1s",
    "rsshub.fetch.read-timeout=2s",
    "rsshub.fetch.per-host-gap=0s",
    "rsshub.ssrf.allow-private-prefixes[0]=127.0.0.1",
    "rsshub.storage.raw-dir=target/test-raw-wake"
})
class FetchWakeupTest {

    /** Long enough for a loopback exchange, short enough that a round that fired on its own would be a failure. */
    private static final Duration DEADLINE = Duration.ofSeconds(10);

    /** FEED_BODY has no items; these tests count entries, so the document has to carry one. */
    private static final String ONE_ITEM = "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title>"
        + "<link>http://localhost/</link><description>d</description>"
        + "<item><title>One</title><link>http://localhost/1</link><guid>wake-1</guid>"
        + "<description>body</description></item></channel></rss>";

    @Autowired
    FetchWakeup wakeup;
    @Autowired
    TaskQueue queue;
    @Autowired
    JdbcTemplate jdbc;

    private LocalFeedServer server;

    @BeforeEach
    void startPublisher() throws Exception {
        server = new LocalFeedServer();
        server.setDefault(ok(ONE_ITEM, RSS_WITH_ETAG));
        jdbc.update("delete from raw_response");
        jdbc.update("delete from entry");
        jdbc.update("delete from scheduled_task");
        jdbc.update("delete from feed");
    }

    @AfterEach
    void stopPublisher() {
        server.close();
    }

    @Test
    void aWakeFetchesWithoutWaitingForTheNextRound() {
        long feedId = feed();
        queue.armNow("fetch", "feed:" + feedId, feedId, Instant.now());
        assertThat(fetchedAt(feedId)).isNull();

        wakeup.wake();

        assertThat(awaitFetch(feedId)).as("the woken round fetched the feed").isNotNull();
        assertThat(entryCount(feedId)).isPositive();
        // The round did its normal bookkeeping too: the row is re-armed for the next interval, not left claimed.
        assertThat(taskState(feedId)).isEqualTo("pending");
        assertThat(fireAt(feedId)).isAfter(Instant.now().plus(Duration.ofMinutes(50)));
    }

    @Test
    void nothingIsFetchedThatWasNotDue() {
        long feedId = feed();
        // Armed an hour out: a wake must scan for what is due, not fetch everything that exists.
        queue.armNow("fetch", "feed:" + feedId, feedId, Instant.now().plus(Duration.ofHours(1)));

        wakeup.wake();
        sleep(Duration.ofSeconds(2));

        assertThat(fetchedAt(feedId)).isNull();
        assertThat(taskState(feedId)).isEqualTo("pending");
    }

    private long feed() {
        String url = server.url("/feed.xml");
        jdbc.update("insert into feed (canonical_url, requested_url) values (?, ?)", url, url);
        return jdbc.queryForObject("select id from feed where canonical_url = ?", Long.class, url);
    }

    /** Polls rather than sleeps once: the work runs on the dispatcher's worker, and the assertion is about the row. */
    private Instant awaitFetch(long feedId) {
        Instant deadline = Instant.now().plus(DEADLINE);
        while (Instant.now().isBefore(deadline)) {
            Instant fetched = fetchedAt(feedId);
            if (fetched != null) {
                return fetched;
            }
            sleep(Duration.ofMillis(50));
        }
        return null;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Instant fetchedAt(long feedId) {
        return instant(feedId, "last_fetched_at");
    }

    private Instant fireAt(long feedId) {
        OffsetDateTime value = jdbc.queryForObject(
            "select fire_at from scheduled_task where kind = 'fetch' and dedupe_key = ?", OffsetDateTime.class,
            "feed:" + feedId);
        return value.toInstant();
    }

    private String taskState(long feedId) {
        return jdbc.queryForObject("select state from scheduled_task where kind = 'fetch' and dedupe_key = ?",
            String.class, "feed:" + feedId);
    }

    private long entryCount(long feedId) {
        return jdbc.queryForObject("select count(*) from entry where feed_id = ?", Long.class, feedId);
    }

    private Instant instant(long feedId, String column) {
        OffsetDateTime value = jdbc.queryForObject("select " + column + " from feed where id = ?",
            OffsetDateTime.class, feedId);
        return value == null ? null : value.toInstant();
    }
}