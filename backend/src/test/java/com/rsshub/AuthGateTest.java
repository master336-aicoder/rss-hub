package com.rsshub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * M0's acceptance gate as tests: an anonymous caller cannot read the subscription list, a watchdog can read
 * health, a cross-site form post cannot log the operator in, and a session can be opened, used and ended.
 * <p>
 * CSRF is exercised the way the SPA will do it — read the {@code XSRF-TOKEN} cookie, echo it in
 * {@code X-XSRF-TOKEN} — rather than with {@code csrf()} from spring-security-test, which sends the
 * BREACH-masked value and would not represent what the browser actually sends to this configuration.
 * The database is in-memory, so the build never touches ./data.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:authgate;DB_CLOSE_DELAY=-1",
    "rsshub.operator.username=admin",
    "rsshub.operator.password=a-test-only-password"
})
@AutoConfigureMockMvc
class AuthGateTest {

    private static final String PASSWORD = "a-test-only-password";

    @Autowired
    MockMvc mvc;

    @Test
    void anonymousCannotReadFeeds() throws Exception {
        mvc.perform(get("/api/feeds")).andExpect(status().isUnauthorized());
    }

    @Test
    void healthStaysAnonymous() throws Exception {
        mvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void unmappedPathIsNotServedByTheApplication() throws Exception {
        // denyAll for anything unlisted: M2 opened the public outlets and the site root, but a URL that neither
        // publishes content nor exposes an endpoint stays behind the auth gate.
        mvc.perform(get("/private/index")).andExpect(status().isUnauthorized());
    }

    @Test
    void crossSiteLoginFormCannotReachLoginProcessing() throws Exception {
        // A hostile page can post username/password, but it cannot read or resend the XSRF-TOKEN cookie, so the
        // request dies in CsrfFilter before credentials are ever looked at. Which 4xx comes back depends on
        // whether an anonymous context already exists at that moment (401 over real HTTP, 403 here), so the
        // contract asserted is the one that matters: rejected, and no authentication happened.
        mvc.perform(post("/api/auth/login").param("username", "admin").param("password", PASSWORD))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void csrfCookieIsReadableByTheBrowserThatNeedsIt() throws Exception {
        mvc.perform(get("/api/auth/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(false));

        Cookie csrf = lastCsrfCookie();
        assertThat(csrf.isHttpOnly()).isFalse();
    }

    @Test
    void loginServesTheListAndLogoutEndsTheSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult login = mvc.perform(loginRequest(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("admin"))
            .andReturn();

        // Authentication rotates the token, and the login filter never continues down the chain, so the rotated
        // value has to be written by the success handler itself — otherwise the SPA's next mutating call is 403.
        // The response carries two XSRF-TOKEN cookies: the clear-then-set pair Spring Security emits on rotation,
        // so the assertion is about the last one (what a browser ends up with), not getCookie()'s first match.
        var csrfCookies = Arrays.stream(login.getResponse().getCookies())
            .filter((c) -> "XSRF-TOKEN".equals(c.getName()))
            .toList();
        assertThat(csrfCookies).isNotEmpty();
        assertThat(csrfCookies.getLast().getValue()).isNotEmpty();

        mvc.perform(get("/api/feeds").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());

        mvc.perform(get("/api/auth/me").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(true))
            .andExpect(jsonPath("$.username").value("admin"));

        mvc.perform(csrfProtected(post("/api/auth/logout").session(session)))
            .andExpect(status().isNoContent());

        mvc.perform(get("/api/feeds").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordGivesProblemJsonNotAnHtmlErrorPage() throws Exception {
        mvc.perform(csrfProtected(post("/api/auth/login")
                .session(new MockHttpSession())
                .param("username", "admin")
                .param("password", "wrong")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.type").value("urn:rsshub:invalid_credentials"));
    }

    @Test
    void stateChangingRequestStillNeedsCsrfOnceLoggedIn() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(loginRequest(session)).andExpect(status().isOk());

        mvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isForbidden());
    }

    @Test
    void secondFactorEnrolmentIsRefusedWhileTheSecretKeyIsUnset() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(loginRequest(session)).andExpect(status().isOk());

        // No RSSHUB_SECRET_KEY means nothing can encrypt the TOTP secret, and storing it in plaintext would be
        // worse than not offering the feature at all (03 §9.1).
        mvc.perform(csrfProtected(post("/api/auth/totp/enroll").session(session)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:secret_key_missing"));
    }

    private MockHttpServletRequestBuilder loginRequest(MockHttpSession session) throws Exception {
        return csrfProtected(post("/api/auth/login")
            .session(session)
            .param("username", "admin")
            .param("password", PASSWORD));
    }

    /** Adds the cookie the browser would send back plus the header the SPA puts on every mutating call. */
    private MockHttpServletRequestBuilder csrfProtected(MockHttpServletRequestBuilder request) throws Exception {
        Cookie csrf = lastCsrfCookie();
        return request.cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
    }

    /**
     * Boots the token by making a plain GET, exactly like the SPA loading for the first time. The value has to
     * come from a real response: CookieCsrfTokenRepository keeps no server-side copy, so it validates the header
     * against the cookie the client sends back.
     */
    private Cookie lastCsrfCookie() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/me")).andReturn();
        Cookie csrf = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrf).as("XSRF-TOKEN cookie must be set on every response for the SPA to read").isNotNull();
        return csrf;
    }
}
