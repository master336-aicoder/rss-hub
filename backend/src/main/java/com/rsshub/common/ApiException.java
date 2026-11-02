package com.rsshub.common;

import org.springframework.http.HttpStatus;

/**
 * A use-case failure that already knows how it must look on the wire.
 *
 * <p>The status and the problem code travel together because a controller that guesses "400 or 409?" from an
 * exception class name gets it wrong in whichever direction it is not looking. Codes are the {@code urn:rsshub:*}
 * suffixes the SPA switches on (03 §10), so they are part of the contract and belong next to the throw, not in a
 * lookup table someone has to remember to update.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    /**
     * 03 §6.3 rule 6: a feed that cannot be built correctly is a 5xx, never a 200 with an HTML error page — a
     * subscriber that stores the page as the feed body is a corruption that takes weeks to notice.
     */
    public static ApiException internal(String code, String message) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, code, message);
    }
}
