package com.rsshub.notify;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The generic receiver (03 §8.3): a plain JSON POST whose body is exactly the nine fields the design fixes, so a
 * rule can point at anything that reads JSON and no vendor SDK is needed.
 *
 * <p>When a secret resolves for the action's {@code secretRef}, the body is signed with
 * {@code X-Rss-Hub-Signature: sha256=<hex>} over the exact bytes sent — signing a re-serialised copy would make the
 * receiver's check depend on key order.
 */
@Component
public class WebhookChannel implements NotifyChannel {

    private final OutboundPoster poster;

    public WebhookChannel(OutboundPoster poster) {
        this.poster = poster;
    }

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public Result send(Notification notification) {
        String body = notification.webhookBody();
        Map<String, String> headers = new LinkedHashMap<>();
        String secret = notification.secret();
        if (secret != null && !secret.isBlank()) {
            headers.put("X-Rss-Hub-Signature", "sha256=" + Signer.hmacSha256Hex(secret, body));
        }
        return poster.post(notification.target(), body, headers);
    }
}
