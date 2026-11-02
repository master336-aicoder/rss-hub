package com.rsshub.security;

import com.rsshub.common.ApiResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Session-cookie auth for the Vue admin. Everything under {@code /api/**} needs an authenticated operator except
 * the auth endpoints; {@code /actuator/health} stays anonymous so a watchdog can poll it; the publish side
 * ({@code /feed.*}, {@code /tag/*}, {@code /category/*}, {@code /blog}) is public by design; and anything else is
 * denied rather than left to a default. Unauthenticated API calls get 401 instead of a login redirect: the caller is
 * a fetch() in a SPA, and a 302 into an HTML page is useless to it.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    public static final String LOGIN_URL = "/api/auth/login";
    public static final String LOGOUT_URL = "/api/auth/logout";
    // Kept in sync with server.servlet.session.cookie.name in application.yml; deleting any other name is a no-op.
    public static final String SESSION_COOKIE_NAME = "RSSHUB_SESSION";

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    LoginAttemptGuard loginAttemptGuard() {
        return new LoginAttemptGuard();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, ApiResponses responses, DbUserDetailsService users,
        PasswordEncoder passwords, SecretCipher cipher, LoginAttemptGuard guard, ApiTokenService tokens)
        throws Exception {
        SpaCsrfTokenRequestHandler requestHandler = new SpaCsrfTokenRequestHandler();

        // The manager the login filter authenticates against: only our provider, never the DAO one.
        OperatorAuthenticationProvider operator =
            new OperatorAuthenticationProvider(users, passwords, cipher, guard);
        org.springframework.security.authentication.ProviderManager loginManager =
            new org.springframework.security.authentication.ProviderManager(operator);

        http
            // An explicit manager, not http.authenticationProvider(): the auto-configured DAO provider is still
            // built from the UserDetailsService bean, and with two providers on one manager the first "yes" wins —
            // which would be the password-only one, silently disabling the second factor.
            .authenticationManager(loginManager)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", LOGIN_URL, LOGOUT_URL, "/api/auth/me").permitAll()
                // The public read side (03 §6.2): three outlets per slice plus the two SSR pages that advertise them.
                // Anonymous by design — a feed behind a session is a feed nobody can subscribe to.
                .requestMatchers("/feed.*", "/feed/*", "/tag/*", "/category/*", "/repack/*", "/blog", "/blog/*",
                    "/event/*/*", "/").permitAll()
                // Machines present a scoped token, never a session (03 §9.1), and the scopes are a ladder rather
                // than three unrelated labels: admin reaches everything the operator can reach, read reaches only
                // reads, and ingest is the write-only rung a CI job gets without seeing the subscription list.
                .requestMatchers("/api/ingest/**").hasAnyAuthority("SCOPE_ingest", "SCOPE_admin")
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/entries/**", "/api/feeds/**")
                .hasAnyAuthority("SCOPE_read", "SCOPE_admin", "ROLE_OPERATOR")
                .requestMatchers("/api/**").hasAnyAuthority("ROLE_OPERATOR", "SCOPE_admin")
                .anyRequest().denyAll())
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(requestHandler)
                // A bearer-token client holds no cookie, so there is no cross-site request to forge.
                .ignoringRequestMatchers(BearerTokenAuthFilter.HAS_BEARER))
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .addFilterBefore(new BearerTokenAuthFilter(tokens), BasicAuthenticationFilter.class)
            .formLogin(form -> form
                .loginProcessingUrl(LOGIN_URL)
                .authenticationDetailsSource(new OperatorAuthenticationProvider.LoginDetailsSource())
                .successHandler((request, response, authentication) -> {
                    // CsrfAuthenticationStrategy already replaced the token, and the login filter short-circuits
                    // the chain, so without this the rotated token would only reach the client on the next GET.
                    writeCsrfCookie(request);
                    responses.writeJson(response, HttpStatus.OK, Map.of("username", authentication.getName()));
                })
                // A locked account is not a wrong password: 429 tells the SPA to back off, and the operator sees
                // why rather than "Invalid username or password" forever. Retry-After is the exact instant, so the
                // message cannot promise a window the in-memory counter does not actually enforce.
                .failureHandler((request, response, exception) -> {
                    if (exception instanceof LockedException) {
                        response.setHeader(HttpHeaders.RETRY_AFTER,
                            String.valueOf(guard.lockExpiresAt(request.getParameter("username")).getEpochSecond()));
                        responses.writeProblem(response, HttpStatus.TOO_MANY_REQUESTS, "account_locked",
                            "Too many failed attempts; this account is locked until the retry time in the header");
                        return;
                    }
                    responses.writeProblem(response, HttpStatus.UNAUTHORIZED, "invalid_credentials",
                        "Invalid username or password");
                }))
            .logout(logout -> logout
                .logoutUrl(LOGOUT_URL)
                .invalidateHttpSession(true)
                // Must match server.servlet.session.cookie.name, or the browser keeps a cookie the server no
                // longer recognises and the operator's next request looks like a fresh anonymous session anyway.
                .deleteCookies(SESSION_COOKIE_NAME)
                .logoutSuccessHandler((request, response, authentication) ->
                    response.setStatus(HttpStatus.NO_CONTENT.value())))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));

        return http.build();
    }

    /**
     * Security only writes the CSRF cookie once the token is actually read, and the SPA reads it from the cookie
     * instead of from a rendered form. Touching the token keeps {@code XSRF-TOKEN} present in the response.
     */
    private static void writeCsrfCookie(HttpServletRequest request) {
        if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
            token.getToken();
        }
    }

    static final class CsrfCookieFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
            writeCsrfCookie(request);
            chain.doFilter(request, response);
        }
    }

    /**
     * The cookie holds the raw token, which is what the SPA echoes in {@code X-XSRF-TOKEN}; form fields and query
     * parameters keep the BREACH-protected value. Splitting resolution by transport is Spring Security's
     * documented pairing for single page apps.
     */
    static final class SpaCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {

        private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
            this.xor.handle(request, response, csrfToken);
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            if (StringUtils.hasText(request.getHeader(csrfToken.getHeaderName()))) {
                return super.resolveCsrfTokenValue(request, csrfToken);
            }
            return this.xor.resolveCsrfTokenValue(request, csrfToken);
        }
    }
}
