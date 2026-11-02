package com.rsshub.notify;

/**
 * 03 §8.3's seam: one interface, one adapter per channel, and nothing else in the application knows that a
 * DingTalk robot and a generic webhook are different protocols.
 *
 * <p>An adapter must not throw. Every transport problem becomes a {@link Result}, because the caller's job is to
 * decide between retry, give up and disable a rule — and an exception path would make that decision depend on which
 * IOException happened to be thrown.
 */
public interface NotifyChannel {

    /** The value stored in {@code rule.actions[].channel} and {@code notification_log.channel}. */
    String name();

    Result send(Notification notification);
}
