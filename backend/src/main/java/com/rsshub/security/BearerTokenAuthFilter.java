package com.rsshub.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code Authorization: Bearer <token>} for machines (FR-3.1's CI job), next to the session cookie for the operator.
 *
 * <p>An unknown or revoked token is <em>not</em> an error here: the filter simply leaves the request anonymous and
 * the authorization rules answer 401. A filter that rejected early would have to duplicate those rules, and the two
 * copies drifting apart is how a scoped token ends up with more rights than its scope.
 */
class BearerTokenAuthFilter extends OncePerRequestFilter {

    /** Machine clients are stateless, so CSRF — which defends a cookie session — does not apply to them. */
    static final RequestMatcher HAS_BEARER = request -> {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7);
    };

    private final ApiTokenService tokens;

    BearerTokenAuthFilter(ApiTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            chain.doFilter(request, response);
            return;
        }
        // A session that already authenticated this request outranks a bearer header, so an operator's own tab
        // cannot be downgraded to a read-only scope by a stray Authorization header.
        if (SecurityContextHolder.getContext().getAuthentication() instanceof UsernamePasswordAuthenticationToken
            && SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }

        Optional<ApiTokenService.TokenRow> row = tokens.findActive(header.substring(7).trim());
        if (row.isPresent()) {
            tokens.touch(row.get().id());
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "token:" + row.get().name(), null,
                List.of(new SimpleGrantedAuthority("SCOPE_" + row.get().scope())));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }
}
