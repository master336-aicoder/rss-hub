package com.rsshub.feedparse;

/** Raised when the bytes are readable text but no feed shape is in them. {@code feedfetch} maps it to parse_error. */
public class FeedParseException extends RuntimeException {

    private final String reason;

    public FeedParseException(String reason) {
        super("feed could not be parsed: " + reason);
        this.reason = reason;
    }

    /** Short and safe to show the operator; never contains the document itself. */
    public String reason() {
        return reason;
    }
}
