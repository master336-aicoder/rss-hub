package com.rsshub.notify;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A loopback receiver for outbound alerts, built on the JDK's own HTTP server so the build gains no dependency
 * (the fetcher's {@code LocalFeedServer} records headers but never a POST body, and a webhook's body is the thing
 * under test).
 *
 * <p>Answers default to DingTalk's success shape, because {@code errcode 0} behind a 200 is the case that a
 * status-code-only check would get wrong. {@link #failNextRequests} exists for the retry ladder: a channel that
 * refuses twice and then accepts has to end up {@code sent} with three attempts.
 */
public final class WebhookSink implements AutoCloseable {

    /** One POST as received. Header names are lowercased so a lookup ignores canonical casing. */
    public record Received(String path, String query, Map<String, List<String>> headers, String body) {
        public String first(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    private final HttpServer server;
    private final int port;
    private final List<Received> requests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger refusals = new AtomicInteger();

    private volatile int status = 200;
    private volatile String body = "{\"errcode\":0,\"errmsg\":\"ok\"}";

    public WebhookSink() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", this::handle);
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String received;
        try (InputStream in = exchange.getRequestBody()) {
            received = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, List<String>> recorded = new java.util.LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> recorded.put(name.toLowerCase(Locale.ROOT), values));
        // The query is kept apart because DingTalk's signature rides in it: /robot?access_token=…&timestamp=…&sign=…
        requests.add(new Received(exchange.getRequestURI().getPath(), exchange.getRequestURI().getRawQuery(),
            recorded, received));

        int answer = refusals.getAndDecrement() > 0 ? 500 : status;
        String payload = answer == 500 ? "{\"errcode\":45009,\"errmsg\":\"from the sink\"}" : body;
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(answer, bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally {
            exchange.close();
        }
    }

    public String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    /** Answer with this status and body from now on. */
    public void respondWith(int status, String body) {
        this.status = status;
        this.body = body;
    }

    /** Refuse the next {@code count} requests with 500, then go back to the standing answer. */
    public void failNextRequests(int count) {
        refusals.set(count);
    }

    public List<Received> requests() {
        return List.copyOf(requests);
    }

    public Received last() {
        return requests.get(requests.size() - 1);
    }

    public void clear() {
        requests.clear();
        refusals.set(0);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
