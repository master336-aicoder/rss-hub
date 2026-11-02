package com.rsshub.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Errors leaving {@code /api/**} are RFC 9457 JSON — a feed consumer that stored a 200 + HTML stack trace as a feed
 * is the failure mode this shape exists to avoid. Framework exceptions (404/405/400) are deliberately not caught
 * here: {@code spring.mvc.problemdetails.enabled} already renders them as Problem JSON, and intercepting them
 * would turn a 405 into a 500.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(SsrfBlockedException.class)
    ProblemDetail ssrfBlocked(SsrfBlockedException e) {
        log.warn("blocked outbound target: {}", e.reason());
        return ApiResponses.problem(HttpStatus.BAD_REQUEST, "ssrf_blocked", "Target address is not allowed");
    }

    /**
     * The reason stays out of the response on purpose: {@code private_target:10.0.0.5} would tell anyone who can
     * reach this port what the internal network looks like, which is the information the guard exists to protect.
     */
    @ExceptionHandler(ApiException.class)
    ProblemDetail apiProblem(ApiException e) {
        return ApiResponses.problem(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail accessDenied(AccessDeniedException e) {
        return ApiResponses.problem(HttpStatus.FORBIDDEN, "forbidden", "Operator permission is insufficient");
    }
}
