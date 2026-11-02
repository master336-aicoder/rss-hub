package com.rsshub.notify;

/**
 * What one channel answered (03 §8.3's {@code send(Notification) -> Result}).
 *
 * <p>The design's two fields, {@code httpStatus} and {@code body}, cannot express the outcome that actually matters
 * for the two Chinese vendors: they answer {@code 200 OK} with a JSON body that says whether the message was
 * accepted — a throttled robot returns 200 and {@code errcode 45009}. So the verdict is part of the result, and
 * each adapter is responsible for reading its own body before setting it.
 */
public record Result(Integer httpStatus, String body, boolean delivered) {

    /** A 2xx the channel itself calls a success. */
    public static Result delivered(Integer httpStatus, String body) {
        return new Result(httpStatus, body, true);
    }

    /** The transport worked and the channel refused: non-2xx, or 2xx with a vendor error code. */
    public static Result refused(Integer httpStatus, String body) {
        return new Result(httpStatus, body, false);
    }

    /** Nothing answered: DNS, timeout, TLS, or an SSRF refusal. There is no status to report. */
    public static Result transport(String error) {
        return new Result(null, error, false);
    }

    /** What lands in {@code notification_log.error} when this attempt did not deliver. */
    public String failure() {
        String detail = body == null || body.isBlank() ? "(no body)" : body;
        if (httpStatus == null) {
            return detail;
        }
        return "HTTP " + httpStatus + ": " + detail;
    }
}
