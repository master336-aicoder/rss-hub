package com.rsshub.common;

/**
 * Raised before any socket is opened, so the reason is always about the target, never about leaked internal
 * details. The {@code reason} is safe to show the operator but the generic handler keeps it out of the body.
 */
public class SsrfBlockedException extends RuntimeException {

    private final String reason;

    public SsrfBlockedException(String reason) {
        super("ssrf blocked: " + reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
