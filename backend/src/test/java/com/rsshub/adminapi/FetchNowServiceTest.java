package com.rsshub.adminapi;

import static com.rsshub.feedfetch.LocalFeedServer.RSS_WITH_ETAG;
import static com.rsshub.feedfetch.LocalFeedServer.Scenario.ok;
import static org.assertj.core.api.Assertions.assertThat;

import com.rsshub.feedfetch.LocalFeedServer;
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
 * What the two subscription buttons promise, measured in wall-clock time: {@code add} and {@code refresh} fetch, and
 * they fetch now. The queue alone cannot show this — every other subscription test runs with the dispatcher off, so
 * it only proves a row was armed.
 *
 * <p>Here the scheduler is on with its round pushed out to an hour, so the only thing that can move a feed is the
 * wake the service asks for. {@code add} is the interesting one: it runs inside a transaction, and a wake fired from
 * inside it would scan a queue whose row is not visible yet — the round would find nothing and the feed would wait
 * for a tick that never comes. That is what the after-commit deferral is for, and it is what these two tests fail to
 * prove if it is removed.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:fetchnow;DB_CLOSE_DELAY=-1",
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
    "rsshub.storage.raw-dir=target/test-raw-now"
})
class FetchNowServiceTest {

    private static final Duration DEADLINE = Duration.ofSeconds(10);

    /** {@code FEED_BODY} carries no items, and these tests count entries. */
    private static final String ONE_ITEM = "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title>"
        + "<link>http://localhost/</link><description>d</description>"
        + "<item><title>One</title><link>http://localhost/1</link><guid>now-1</guid>"
        + "<description>body</description></item></channel></rss>";

    @Autowired
    SubscriptionService subscriptions;
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
    void addingASubscriptionFetchesItWithoutWaitingForARound() {
        long id = subscriptions.add(server.url("/feed.xml"), "Tech").id();

        assertThat(awaitEntries(id)).as("the after-commit wake ran the first fetch").isPositive();
        assertThat(fetchedAt(id)).isNotNull();
    }

    @Test
    void refreshingPushesTheWorkForwardAndRunsIt() throws Exception {
        long id = subscriptions.add(server.url("/second.xml"), null).id();
        awaitEntries(id);
        // Push the schedule out an hour: the only way this feed moves again is the button. The timestamp is computed
        // here rather than with dateadd(), which is H2-only.
        jdbc.update("update scheduled_task set fire_at = ? where kind = 'fetch' and dedupe_key = ?",
            java.sql.Timestamp.from(Instant.now().plus(Duration.ofHours(1))), "feed:" + id);
        jdbc.update("update feed set last_fetched_at = null where id = ?", id);

        subscriptions.refresh(id);

        assertThat(fireAt(id)).as("armNow pulled the due time back to now").isBefore(Instant.now().plus(Duration.ofMinutes(5)));
        assertThat(awaitFetch(id)).as("the woken round fetched it").isNotNull();
    }

    private long awaitEntries(long feedId) {
        Instant deadline = Instant.now().plus(DEADLINE);
        while (Instant.now().isBefore(deadline)) {
            long count = entries(feedId);
            if (count > 0) {
                return count;
            }
            pause();
        }
        return entries(feedId);
    }

    private Instant awaitFetch(long feedId) {
        Instant deadline = Instant.now().plus(DEADLINE);
        while (Instant.now().isBefore(deadline)) {
            Instant fetched = fetchedAt(feedId);
            if (fetched != null) {
                return fetched;
            }
            pause();
        }
        return fetchedAt(feedId);
    }

    private static void pause() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private long entries(long feedId) {
        return jdbc.queryForObject("select count(*) from entry where feed_id = ?", Long.class, feedId);
    }

    private Instant fetchedAt(long feedId) {
        OffsetDateTime value = jdbc.queryForObject("select last_fetched_at from feed where id = ?",
            OffsetDateTime.class, feedId);
        return value == null ? null : value.toInstant();
    }

    private Instant fireAt(long feedId) {
        return jdbc.queryForObject("select fire_at from scheduled_task where kind = 'fetch' and dedupe_key = ?",
            OffsetDateTime.class, "feed:" + feedId).toInstant();
    }
}
