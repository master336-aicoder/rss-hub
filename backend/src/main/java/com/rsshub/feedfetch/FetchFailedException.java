package com.rsshub.feedfetch;

/**
 * A fetch that never reached a decision point, i.e. there is no HTTP status to reason about.
 * <p>
 * {@link Kind#SIZE_LIMIT} is separated from the rest on purpose: exceeding our own byte cap is not the source
 * behaving badly, so 03 §4.3 halves the backoff for it and does not count it against {@code fail_count}.
 */
public class FetchFailedException extends RuntimeException {

    public enum Kind {
        TIMEOUT,
        SIZE_LIMIT,
        NETWORK,
        REDIRECT_LIMIT,
        MALFORMED_URL,
        INTERRUPTED
    }

    private final Kind kind;

    public FetchFailedException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
