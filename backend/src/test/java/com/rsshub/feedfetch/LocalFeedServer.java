package com.rsshub.feedfetch;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * A loopback feed publisher for tests, built on the JDK's own HTTP server so the build gains no dependency.
 * <p>
 * It answers on {@code 127.0.0.1}, which {@code SsrfGuard} refuses by default — so the tests run with
 * {@code rsshub.ssrf.allow-private-prefixes[0]=127.0.0.1}, the same escape hatch the design gives an operator who
 * subscribes to a feed on their own LAN (03 §9.2).
 * <p>
 * Responses are per path with a default, because a redirect scenario has to answer differently on the target path
 * than on the one that moved.
 */
public final class LocalFeedServer implements AutoCloseable {

    public static final String FEED_BODY = "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title>"
        + "<link>http://localhost/</link><description>d</description></channel></rss>";
    public static final Map<String, String> RSS_WITH_ETAG = Map.of("Content-Type", "application/rss+xml", "ETag", "\"v1\"");

    /** One recorded request, with header names lowercased so lookups do not depend on canonical casing. */
    public record Received(String path, Map<String, List<String>> headers) {
        public String first(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    /** What to answer with. {@code chunked} means "do not send Content-Length", which is how the size cap bites. */
    public record Scenario(int status, byte[] body, Map<String, String> headers, Duration delay, boolean chunked) {

        public static Scenario ok(String body, Map<String, String> headers) {
            return new Scenario(200, body.getBytes(StandardCharsets.UTF_8), headers, Duration.ZERO, false);
        }

        public static Scenario of(int status) {
            return new Scenario(status, new byte[0], Map.of("Content-Type", "application/rss+xml"), Duration.ZERO,
                false);
        }

        public static Scenario withHeaders(int status, Map<String, String> headers) {
            return new Scenario(status, new byte[0], headers, Duration.ZERO, false);
        }

        public static Scenario redirect(int status, String location) {
            return new Scenario(status, new byte[0], Map.of("Location", location), Duration.ZERO, false);
        }

        public static Scenario after(Duration wait, int status) {
            return new Scenario(status, new byte[0], Map.of(), wait, false);
        }

        public static Scenario chunked(int status, int sizeBytes) {
            return new Scenario(status, new byte[sizeBytes], Map.of("Content-Type", "application/rss+xml"),
                Duration.ZERO, true);
        }

        Scenario withHeader(String name, String value) {
            Map<String, String> merged = new LinkedHashMap<>(headers);
            merged.put(name, value);
            return new Scenario(status, body, Map.copyOf(merged), delay, chunked);
        }
    }

    private final HttpServer server;
    private final int port;
    private final Map<String, Scenario> byPath = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<Received> requests = Collections.synchronizedList(new ArrayList<>());

    private volatile Scenario defaultScenario = Scenario.ok(FEED_BODY, RSS_WITH_ETAG);

    public LocalFeedServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", this::handle);
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        Map<String, List<String>> recorded = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> recorded.put(name.toLowerCase(Locale.ROOT), values));
        requests.add(new Received(exchange.getRequestURI().getPath(), recorded));

        Scenario scenario = byPath.getOrDefault(exchange.getRequestURI().getPath(), defaultScenario);
        if (!scenario.delay().isZero()) {
            try {
                Thread.sleep(scenario.delay().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            scenario.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
            if (scenario.status() == 304 || scenario.body().length == 0) {
                // RFC 9110: a 304 and a redirect have no body, and the JDK server wants -1 to say so explicitly.
                exchange.sendResponseHeaders(scenario.status(), -1);
            } else if (scenario.chunked()) {
                exchange.sendResponseHeaders(scenario.status(), 0);
                exchange.getResponseBody().write(scenario.body());
            } else {
                exchange.sendResponseHeaders(scenario.status(), scenario.body().length);
                exchange.getResponseBody().write(scenario.body());
            }
        } catch (IOException ignored) {
            // The client gave up (timeout, or the size cap cut the stream). That is the case under test.
        } finally {
            exchange.close();
        }
    }

    public String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    public void setDefault(Scenario scenario) {
        defaultScenario = scenario;
        byPath.clear();
    }

    public void on(String path, Scenario scenario) {
        byPath.put(path, scenario);
    }

    public List<Received> requests() {
        return List.copyOf(requests);
    }

    public Received lastRequest() {
        return requests.get(requests.size() - 1);
    }

    public int port() {
        return port;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
