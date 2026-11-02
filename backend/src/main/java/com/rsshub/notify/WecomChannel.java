package com.rsshub.notify;

import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * A WeCom group robot (03 §8.3). The webhook URL's {@code key} is the credential, so unlike DingTalk there is
 * nothing to sign — and unlike a generic webhook, a throttled send still answers {@code 200 OK} with
 * {@code errcode 45009}, which is why the verdict comes from {@link VendorVerdict} rather than the status code.
 *
 * <p>Markdown is chosen over text because it renders the link; {@link ChatFormat} keeps the body inside a byte
 * budget, because this robot's own page publishes its message ceilings in bytes and throttling aside, length is the
 * one reason a send comes back refused.
 */
@Component
public class WecomChannel implements NotifyChannel {

    private final OutboundPoster poster;

    public WecomChannel(OutboundPoster poster) {
        this.poster = poster;
    }

    @Override
    public String name() {
        return "wecom";
    }

    @Override
    public Result send(Notification notification) {
        String body = JsonBody.write(JsonBody.pairs(
            "msgtype", "markdown",
            "markdown", JsonBody.pairs("content", ChatFormat.markdown(notification))));
        return VendorVerdict.of(poster.post(notification.target(), body, Map.of()));
    }
}
