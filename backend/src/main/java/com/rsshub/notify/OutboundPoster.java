package com.rsshub.notify;

import com.rsshub.feedfetch.DnsPins;
import com.rsshub.security.SsrfGuard;
import com.rsshub.security.SsrfGuard.ValidatedTarget;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The one place {@code notify} opens a socket, and the reason 02 §3 rule 2 can stay true: an alert's URL comes from
 * the same operator-controlled config as a subscription's, so it goes through the same guard and the same DNS pin as
 * {@link com.rsshub.feedfetch.HttpFeedFetcher} does. A second, unguarded client would be a way to reach
 * {@code 169.254.169.254} that the fetcher's tests do not cover.
 *
 * <p>Redirects are never followed: a notification is a single POST to a URL the operator typed, and a 3xx that
 * carried the body to another host would be an exfiltration path with a signature header on it.
 */
@Component
public class OutboundPoster {

    private static final Logger log = LoggerFactory.getLogger(OutboundPoster.class);

    /** Enough of a vendor's JSON to explain a refusal, and short enough for {@code notification_log.error}. */
    private static final int RESPONSE_MAX_BYTES = 4096;

    private final HttpClient client;
    private final SsrfGuard guard;
    private final NotifyPolicy policy;

    public OutboundPoster(SsrfGuard guard, NotifyPolicy policy) {
        this.guard = guard;
        this.policy = policy;
        this.client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(policy.connectTimeout())
            .build();
    }

    public Result post(String url, String body, Map<String, String> headers) {
        URI uri;
        ValidatedTarget target;
        try {
            uri = URI.create(url.trim().replaceAll(" ", "%20"));
            target = guard.validate(url);
        } catch (RuntimeException e) {
            // A refused target will still be refused on the next attempt, so this is reported as a transport result
            // and the rule's own consecutive-failure counter is what eventually stops the noise.
            log.atWarn().addKeyValue("url", url).log("notification target refused: {}", e.toString());
            return Result.transport("refused: " + e.getMessage());
        }
        try (DnsPins.Scope pin = DnsPins.pin(uri.getHost(), target.resolvedAddresses())) {
            return exchange(uri, body, headers);
        } catch (RuntimeException e) {
            return Result.transport("could not pin " + uri.getHost() + ": " + e.getMessage());
        }
    }

    private Result exchange(URI uri, String body, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(policy.readTimeout())
            .header("Content-Type", "application/json; charset=utf-8")
            .header("User-Agent", policy.userAgent())
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        HttpResponse<InputStream> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            return Result.transport(uri + " timed out after " + policy.readTimeout());
        } catch (IOException e) {
            return Result.transport(uri + " failed: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.transport("interrupted while sending");
        }
        try (InputStream stream = response.body()) {
            String text = read(stream);
            return response.statusCode() >= 200 && response.statusCode() < 300
                ? Result.delivered(response.statusCode(), text)
                : Result.refused(response.statusCode(), text);
        } catch (IOException e) {
            return Result.transport(uri + " answered " + response.statusCode() + " but the body failed: " + e);
        }
    }

    private static String read(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int total = 0;
        int read;
        while (total < RESPONSE_MAX_BYTES && (read = stream.read(buffer, 0,
            Math.min(buffer.length, RESPONSE_MAX_BYTES - total))) >= 0) {
            out.write(buffer, 0, read);
            total += read;
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
