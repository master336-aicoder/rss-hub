package com.rsshub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * 03 §9.1's three promises end to end: a second factor enrolled through the API, a login that stops being enough
 * on the password alone once it is, and a lockout that answers 429 instead of "wrong password" forever.
 *
 * <p>That the password-only case has to fail is the whole point of this file: Spring Boot wires a
 * {@code DaoAuthenticationProvider} from any {@code UserDetailsService} bean it finds, and if that provider ever
 * shared the login manager with ours, a correct password would open the session and the TOTP check would simply
 * never run.
 *
 * <p>The lockout cases use usernames that do not exist. The guard counts per name, so hammering {@code admin} here
 * would lock the rest of the class out of its own account, and the branch under test runs before the account lookup.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:totpgate;DB_CLOSE_DELAY=-1",
    "rsshub.operator.username=admin",
    "rsshub.operator.password=a-test-only-password",
    "rsshub.fetch.enabled=false"
})
@AutoConfigureMockMvc
class TotpLoginGateTest {

    private static final String PASSWORD = "a-test-only-password";

    /** A throwaway key for this JVM only; an install's real key never appears in a test file. */
    @DynamicPropertySource
    static void encryptionKey(DynamicPropertyRegistry registry) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        registry.add("rsshub.security.secret-key", () -> Base64.getEncoder().encodeToString(key));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient db;

    @Autowired
    ObjectMapper json;

    @BeforeEach
    void startWithoutASecondFactor() throws Exception {
        db.sql("update app_user set totp_secret = null").update();
        // A successful login is also what clears this username's failure counter in the in-memory guard.
        login();
    }

    @Test
    void enrolmentStaysInactiveUntilACodeHasBeenProved() throws Exception {
        MockHttpSession session = login();
        String secret = enroll(session);

        assertThat(me(session)).contains("\"totpEnabled\":false");
        assertThat(storedSecret()).as("enrol alone must not store anything").isNull();

        mvc.perform(write(session, post("/api/auth/totp/activate"),
            Map.of("secret", secret, "code", "000000")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:bad_code"));
        assertThat(storedSecret()).isNull();

        mvc.perform(write(session, post("/api/auth/totp/activate"),
            Map.of("secret", secret, "code", code(secret))))
            .andExpect(status().isNoContent());

        // What is stored is ciphertext, so a database dump alone cannot clone an authenticator.
        assertThat(storedSecret()).startsWith("v1:").doesNotContain(secret);
        assertThat(me(session)).contains("\"totpEnabled\":true");
    }

    @Test
    void aPasswordAloneStopsBeingEnoughAndACodeStartsBeingRequired() throws Exception {
        String secret = enrollAndActivate(login());

        mvc.perform(loginRequest(null))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.type").value("urn:rsshub:invalid_credentials"));

        mvc.perform(loginRequest(code(secret)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("admin"));

        mvc.perform(loginRequest("123456"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.type").value("urn:rsshub:invalid_credentials"));
    }

    @Test
    void removingTheSecondFactorNeedsThePasswordNotTheCode() throws Exception {
        // The session has to be opened before the factor exists: once it does, a password-only login is refused and
        // this test would be measuring the wrong thing.
        MockHttpSession session = login();
        enrollAndActivate(session);

        mvc.perform(write(session, post("/api/auth/totp/deactivate"), Map.of("password", "wrong")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:rsshub:password_required"));

        assertThat(me(session)).contains("\"totpEnabled\":true");

        mvc.perform(write(session, post("/api/auth/totp/deactivate"), Map.of("password", PASSWORD)))
            .andExpect(status().isNoContent());

        assertThat(storedSecret()).isNull();
        mvc.perform(loginRequest(null)).andExpect(status().isOk());
    }

    @Test
    void theStateThatDoesNotExistIsRefusedRatherThanIgnored() throws Exception {
        MockHttpSession session = login();

        mvc.perform(write(session, post("/api/auth/totp/deactivate"), Map.of("password", PASSWORD)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("urn:rsshub:totp_inactive"));

        String secret = enrollAndActivate(session);
        mvc.perform(write(session, post("/api/auth/totp/enroll")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("urn:rsshub:totp_already_active"));
        // Enrolment is the only call that ever hands the secret out, and it refuses a second time.
        assertThat(storedSecret()).startsWith("v1:").doesNotContain(secret);
    }

    @Test
    void fiveFailuresLockTheNameAndTheLockRefusesTheRightPasswordToo() throws Exception {
        String victim = "not-the-operator";
        for (int attempt = 0; attempt < LoginAttemptGuard.maxFailures(); attempt++) {
            mvc.perform(csrf(post("/api/auth/login").session(new MockHttpSession())
                    .param("username", victim).param("password", "guess-" + attempt)))
                .andExpect(status().isUnauthorized());
        }

        // Once the name is locked the credential is not even looked at, so a guess that happens to be right
        // still cannot shorten the window.
        mvc.perform(csrf(post("/api/auth/login").session(new MockHttpSession())
                .param("username", victim).param("password", PASSWORD)))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.type").value("urn:rsshub:account_locked"))
            .andExpect(header().string("Retry-After", matchesPattern("\\d+")));
    }

    @Test
    void aSuccessfulLoginClearsTheFailureCounter() throws Exception {
        String victim = "counter-reset-check";
        for (int attempt = 0; attempt < LoginAttemptGuard.maxFailures() - 1; attempt++) {
            mvc.perform(csrf(post("/api/auth/login").session(new MockHttpSession())
                    .param("username", victim).param("password", "guess")))
                .andExpect(status().isUnauthorized());
        }

        mvc.perform(csrf(post("/api/auth/login").session(new MockHttpSession())
                .param("username", victim).param("password", PASSWORD)))
            .andExpect(status().isUnauthorized());

        // Four failures for one name must not spill onto the account that actually exists.
        mvc.perform(loginRequest(null)).andExpect(status().isOk());
    }

    @Test
    void aBearerTokenSessionCannotEnrolASecondFactorForTheOperator() throws Exception {
        // /api/auth/totp/** is operator-only; a machine credential has no business reaching it.
        mvc.perform(get("/api/auth/totp/enroll").header("Authorization", "Bearer anything"))
            .andExpect(status().isUnauthorized());
    }

    private String enrollAndActivate(MockHttpSession session) throws Exception {
        String secret = enroll(session);
        mvc.perform(write(session, post("/api/auth/totp/activate"),
            Map.of("secret", secret, "code", code(secret))))
            .andExpect(status().isNoContent());
        return secret;
    }

    private String enroll(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(write(session, post("/api/auth/totp/enroll")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.digits").value(6))
            .andExpect(jsonPath("$.periodSeconds").value(30))
            .andExpect(jsonPath("$.otpauthUri").value(org.hamcrest.Matchers.containsString("otpauth://totp/")))
            .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("secret").asString();
    }

    /** The code an authenticator would show right now, derived from the secret the app just handed back. */
    private static String code(String base32Secret) {
        return TotpService.codes(TotpService.fromBase32(base32Secret),
            Instant.now().getEpochSecond() / TotpService.periodSeconds());
    }

    private MockHttpSession login() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(loginRequest(null).session(session)).andExpect(status().isOk());
        return session;
    }

    private MockHttpServletRequestBuilder loginRequest(String code) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/auth/login")
            .param("username", "admin")
            .param("password", PASSWORD);
        return csrf(code == null ? request : request.param("code", code));
    }

    private String storedSecret() {
        return db.sql("select totp_secret from app_user where username = 'admin'")
            .query(String.class)
            .optional()
            .orElse(null);
    }

    private String me(MockHttpSession session) throws Exception {
        return mvc.perform(get("/api/auth/me").session(session))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private MockHttpServletRequestBuilder write(MockHttpSession session, MockHttpServletRequestBuilder request)
        throws Exception {
        return write(session, request, null);
    }

    private MockHttpServletRequestBuilder write(MockHttpSession session, MockHttpServletRequestBuilder request,
        Object body) throws Exception {
        Cookie csrf = csrfCookie();
        MockHttpServletRequestBuilder armed = request.session(session).cookie(csrf)
            .header("X-XSRF-TOKEN", csrf.getValue());
        return body == null ? armed
            : armed.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }

    private MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request) throws Exception {
        Cookie csrf = csrfCookie();
        return request.cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
    }

    /**
     * One cookie value per request: {@code CookieCsrfTokenRepository} mints a token per session that has none, so
     * reading twice would send back the second one in the header and the first one in the cookie.
     */
    private Cookie csrfCookie() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/me")).andReturn();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).as("XSRF-TOKEN must be readable by the browser that echoes it").isNotNull();
        return cookie;
    }
}
