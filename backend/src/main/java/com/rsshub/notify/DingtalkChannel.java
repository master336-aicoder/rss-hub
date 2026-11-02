package com.rsshub.notify;

import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * A DingTalk custom group robot (03 §8.3). Verified against the vendor's own security-settings page on 2026-10-18:
 * the signed string is {@code <timestamp in ms> + "\n" + secret}, keyed by the secret itself, HmacSHA256 → Base64 →
 * URL-encoded, and both values travel as {@code timestamp} and {@code sign} query parameters. The server accepts a
 * timestamp up to one hour old.
 *
 * <p>A robot with no 加签 secret cannot be identified by anything but a keyword in the message body, so the rule has
 * to carry a {@code secretRef} that resolves — otherwise the send is refused before the socket opens, with a reason
 * that says what to fix.
 */
@Component
public class DingtalkChannel implements NotifyChannel {

    private final OutboundPoster poster;

    public DingtalkChannel(OutboundPoster poster) {
        this.poster = poster;
    }

    @Override
    public String name() {
        return "dingtalk";
    }

    @Override
    public Result send(Notification notification) {
        String secret = notification.secret();
        if (secret == null || secret.isBlank()) {
            return Result.transport("no signing secret for " + notification.secretRef()
                + ": set RSSHUB_NOTIFY_SECRETS_* and re-check the rule's secretRef");
        }
        String body = JsonBody.write(JsonBody.pairs(
            "msgtype", "text",
            "text", JsonBody.pairs("content", ChatFormat.plain(notification))));
        return VendorVerdict.of(poster.post(signed(notification.target(), secret), body, Map.of()));
    }

    /** The target already carries {@code ?access_token=…}, so the two signature params are appended, not replaced. */
    private static String signed(String target, String secret) {
        long timestamp = System.currentTimeMillis();
        String sign = Signer.urlEncode(Signer.hmacSha256Base64(secret, timestamp + "\n" + secret));
        return target + (target.indexOf('?') >= 0 ? "&" : "?") + "timestamp=" + timestamp + "&sign=" + sign;
    }
}
