package com.rsshub.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One id per request, echoed to the client and put in the logging MDC, so "which log lines belong to this fetch"
 * is answerable without a tracing backend. Inbound headers are ignored — trusting a caller-supplied id makes the
 * trail useless the moment someone spoofs it.
 */
@Component
// Before Spring Security's own filter (order -100), otherwise a rejected request logs with no traceId.
@Order(-150)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {

        String traceId = UUID.randomUUID().toString();
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
