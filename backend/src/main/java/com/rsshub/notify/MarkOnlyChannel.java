package com.rsshub.notify;

import org.springframework.stereotype.Component;

/**
 * The channel that never leaves the process (03 §8.3's {@code markOnly}): the rule has already recorded the hit in
 * {@code notification_log}, and the inbox badge is what the operator wanted. It exists so a rule can be built and
 * tested end-to-end — match, suppress, quiet hours, delivery — without an external receiver, and so "收到但没有推送"
 * is a state a rule can legitimately be in.
 */
@Component
public class MarkOnlyChannel implements NotifyChannel {

    @Override
    public String name() {
        return "markOnly";
    }

    @Override
    public Result send(Notification notification) {
        return Result.delivered(null, "inbox mark only");
    }
}
