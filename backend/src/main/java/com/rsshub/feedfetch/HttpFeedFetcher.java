package com.rsshub.feedfetch;

import com.rsshub.feedfetch.FetchFailedException.Kind;
import com.rsshub.security.SsrfGuard;
import com.rsshub.security.SsrfGuard.ValidatedTarget;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The only place in the application that talks to a feed publisher. 02 §3 rule 2 makes this the single egress for
 * subscription content so that SSRF defence and per-host politeness cannot be bypassed by a second client.
 *
 * <h2>Redirects are followed by hand</h2>
 * {@code followRedirects(ALWAYS)} would resolve and connect to every hop without consulting {@link SsrfGuard},
 * which is the classic way to turn "I validated the URL" into "I fetched {@code http://169.254.169.254}". So the
 * client is configured {@code NEVER} and each hop is validate &rarr; pin &rarr; request.
 *
 * <h2>Compression</h2>
 * The request deliberately does not set {@code Accept-Encoding}. {@code java.net.http} adds {@code gzip} itself
 * and inflates the body only when it set the header; setting it by hand would hand the parser raw gzipped bytes.
 * Brotli is therefore not requested — asking for it would need a dependency this project does not have.
 */
@Component
public class HttpFeedFetcher {

    private static final Logger log = LoggerFactory.getLogger(HttpFeedFetcher.class);

    /** 03 §4.3 step 1. The q-values keep XML/JSON ahead of an HTML page that answers anyway. */
    private static final String ACCEPT = "application/rss+xml, application/atom+xml, application/feed+json, "
        + "application/xml;q=0.9, */*;q=0.1";

    private static final int READ_CHUNK = 16 * 1024;

    private final HttpClient client;
    private final SsrfGuard guard;
    private final FetchPolicy policy;

    public HttpFeedFetcher(SsrfGuard guard, FetchPolicy policy) {
        this.guard = guard;
        this.policy = policy;
        this.client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(policy.connectTimeout())
            .build();
    }

    /**
     * @param finalUrl the URL that produced {@code status}; differs from the request when the host moved
     * @param canonicalMoved true only after a 301/308 — the two hops 03 §4.3 allows to rewrite
     *        {@code feed.canonical_url}. A 302/307 is a detour, not a move.
     * @param body the response body, empty for 304, never larger than {@code maxBytes}
     * @param elapsedMs wall time of the whole exchange, including redirect hops, for {@code feed.avg_fetch_ms}
     */
    public record Response(int status, String finalUrl, boolean canonicalMoved, String contentType, String etag,
        String lastModified, String retryAfter, byte[] body, long elapsedMs) {

        public boolean isContent() {
            return status >= 200 && status < 300;
        }

        public boolean isNotModified() {
            return status == 304;
        }
    }

    /**
     * GETs {@code url}, following at most {@code maxRedirects} hops.
     *
     * @param ifNoneMatch the stored {@code ETag}; sent on the first hop only, because replaying it onto a redirect
     *        target could make a different publisher answer 304 for content we have never seen
     * @throws FetchFailedException for every outcome that has no status code to reason about
     * @throws com.rsshub.common.SsrfBlockedException when the guard refuses this URL or any hop
     */
    public Response fetch(String url, String ifNoneMatch, String ifModifiedSince) {
        Instant started = Instant.now();
        Instant deadline = started.plus(policy.readTimeout());
        String current = url;
        boolean canonicalMoved = false;
        int hops = 0;

        while (true) {
            URI uri = toUri(current, Kind.MALFORMED_URL);
            ValidatedTarget target = guard.validate(current);
            HopResult hop;
            try (DnsPins.Scope pin = DnsPins.pin(target.uri().getHost(), target.resolvedAddresses())) {
                hop = perform(build(uri, hops == 0 ? ifNoneMatch : null, hops == 0 ? ifModifiedSince : null,
                    deadline), deadline);
            }

            if (!isRedirect(hop.status)) {
                return new Response(hop.status, current, canonicalMoved, hop.contentType, hop.etag,
                    hop.lastModified, hop.retryAfter, hop.body,
                    Duration.between(started, Instant.now()).toMillis());
            }
            if (hop.status == 301 || hop.status == 308) {
                canonicalMoved = true;
            }
            if (hops >= policy.maxRedirects()) {
                throw new FetchFailedException(Kind.REDIRECT_LIMIT,
                    "still redirecting after " + policy.maxRedirects() + " hops from " + url, null);
            }
            if (hop.location == null || hop.location.isBlank()) {
                throw new FetchFailedException(Kind.MALFORMED_URL,
                    "redirect " + hop.status + " without a Location header from " + current, null);
            }
            String next = resolve(current, hop.location);
            log.atDebug().addKeyValue("from", current).addKeyValue("to", next).addKeyValue("status", hop.status)
                .log("feed redirect followed");
            hops++;
            current = next;
        }
    }

    private HopResult perform(HttpRequest request, Instant deadline) {
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new FetchFailedException(Kind.TIMEOUT, request.uri() + " timed out: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new FetchFailedException(Kind.NETWORK, request.uri() + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchFailedException(Kind.INTERRUPTED, request.uri() + " interrupted", e);
        }

        HttpHeaders headers = response.headers();
        HopResult result = new HopResult();
        result.status = response.statusCode();
        result.contentType = header(headers, "content-type");
        result.etag = header(headers, "etag");
        result.lastModified = header(headers, "last-modified");
        result.retryAfter = header(headers, "retry-after");
        result.location = header(headers, "location");

        long declared = headers.firstValueAsLong("content-length").orElse(-1L);
        if (declared > policy.maxBytes()) {
            closeQuietly(response.body());
            throw new FetchFailedException(Kind.SIZE_LIMIT,
                request.uri() + " declares " + declared + " bytes over the " + policy.maxBytes() + " cap", null);
        }
        try (InputStream body = response.body()) {
            result.body = readBounded(body, deadline, request.uri());
        } catch (IOException e) {
            throw new FetchFailedException(Kind.NETWORK, "reading " + request.uri() + ": " + e, e);
        }
        return result;
    }

    /**
     * 03 §4.3 step 2: read until the body ends or the cap is hit, then stop — an oversize response must not be
     * buffered even long enough to be rejected.
     *
     * <p>The deadline is checked between reads, so a publisher that stalls the socket mid-body is only noticed when
     * the next chunk arrives; {@code java.net.http} exposes no socket read timeout, and the connect and
     * response-header timeouts are both enforced by the client itself.
     */
    private byte[] readBounded(InputStream body, Instant deadline, URI uri) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(READ_CHUNK);
        byte[] buffer = new byte[READ_CHUNK];
        int total = 0;
        while (true) {
            if (Instant.now().isAfter(deadline)) {
                throw new FetchFailedException(Kind.TIMEOUT, uri + " exceeded the read budget", null);
            }
            int read = body.read(buffer);
            if (read < 0) {
                return out.toByteArray();
            }
            total += read;
            if (total > policy.maxBytes()) {
                throw new FetchFailedException(Kind.SIZE_LIMIT,
                    uri + " streamed past the " + policy.maxBytes() + " byte cap", null);
            }
            out.write(buffer, 0, read);
        }
    }

    private HttpRequest build(URI uri, String ifNoneMatch, String ifModifiedSince, Instant deadline) {
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero()) {
            throw new FetchFailedException(Kind.TIMEOUT, uri + " has no read budget left before connecting", null);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(remaining)
            // java.net.http has no HttpClient.Builder#userAgent, so the identity is a plain request header.
            .header("User-Agent", policy.userAgent())
            .header("Accept", ACCEPT)
            .GET();
        // RFC 9110: a conditional must never be combined with If-Modified-Since the client has no date for, and an
        // absent ETag means the stored Last-Modified is the only validator worth sending.
        if (ifNoneMatch != null && !ifNoneMatch.isBlank()) {
            builder.header("If-None-Match", ifNoneMatch);
        }
        if (ifModifiedSince != null && !ifModifiedSince.isBlank()) {
            builder.header("If-Modified-Since", ifModifiedSince);
        }
        return builder.build();
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String header(HttpHeaders headers, String name) {
        Optional<String> value = headers.firstValue(name);
        return value.orElse(null);
    }

    private static URI toUri(String raw, Kind failureKind) {
        try {
            return URI.create(raw.trim().replaceAll(" ", "%20"));
        } catch (IllegalArgumentException e) {
            throw new FetchFailedException(failureKind, "not a usable URL: " + raw, e);
        }
    }

    /** Absolute against the current URL, because publishers do emit relative {@code Location} values. */
    private static String resolve(String current, String location) {
        URI base = toUri(current, Kind.MALFORMED_URL);
        URI target = base.resolve(toUri(location, Kind.MALFORMED_URL));
        if (!target.isAbsolute() || target.getHost() == null) {
            throw new FetchFailedException(Kind.MALFORMED_URL,
                "redirect to [" + location + "] from [" + current + "] is not absolute", null);
        }
        return target.toString();
    }

    private static void closeQuietly(InputStream body) {
        try {
            body.close();
        } catch (IOException | RuntimeException e) {
            log.atDebug().setCause(e).log("oversize response body could not be closed cleanly");
        }
    }

    /** Mutable on purpose: eight optional response fields plus a body, and every caller wants a different subset. */
    private static final class HopResult {
        int status;
        String contentType;
        String etag;
        String lastModified;
        String retryAfter;
        String location;
        byte[] body;
    }
}
