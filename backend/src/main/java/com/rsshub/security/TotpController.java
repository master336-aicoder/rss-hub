package com.rsshub.security;

import com.rsshub.common.ApiException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * TOTP enrolment for the single operator account (03 §9.1). Three calls, and the secret only ever travels once:
 * {@code enroll} hands it back so an authenticator app can read the QR code, and nothing after that returns it.
 *
 * <h2>Why activation is a separate call</h2>
 * Storing the secret at {@code enroll} would mean a half-finished enrolment locks the operator out of an account
 * whose phone never got the code. So the secret is only written once a code derived from it has been proved — and
 * {@code deactivate} checks the password rather than the code, because the case it exists for is "my phone is gone".
 */
@RestController
@RequestMapping("/api/auth/totp")
public class TotpController {

    public record EnrollRequest() {
    }

    public record ActivateRequest(String secret, String code) {
    }

    public record DeactivateRequest(String password) {
    }

    private final DbUserDetailsService users;
    private final SecretCipher cipher;
    private final org.springframework.security.crypto.password.PasswordEncoder passwords;

    public TotpController(DbUserDetailsService users, SecretCipher cipher,
        org.springframework.security.crypto.password.PasswordEncoder passwords) {
        this.users = users;
        this.cipher = cipher;
        this.passwords = passwords;
    }

    @PostMapping("/enroll")
    public Map<String, Object> enroll(Authentication authentication) {
        if (!cipher.enabled()) {
            throw ApiException.badRequest("secret_key_missing",
                "Set RSSHUB_SECRET_KEY before enrolling a second factor");
        }
        DbUserDetailsService.StoredOperator operator = requireOperator(authentication);
        if (operator.totpSecret() != null) {
            throw ApiException.conflict("totp_already_active",
                "This account already has a second factor; deactivate it first");
        }
        byte[] secret = TotpService.newSecret();
        String base32 = TotpService.base32(secret);
        return Map.of(
            "secret", base32,
            "otpauthUri", TotpService.otpauthUri("rss-hub", operator.username(), base32),
            "digits", TotpService.digits(),
            "periodSeconds", TotpService.periodSeconds());
    }

    @PostMapping("/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activate(Authentication authentication, @RequestBody ActivateRequest request) {
        DbUserDetailsService.StoredOperator operator = requireOperator(authentication);
        if (request.secret() == null || request.secret().isBlank()) {
            throw ApiException.badRequest("secret_required", "Enrol first, then activate with the code");
        }
        if (!TotpService.matches(request.secret().trim(), request.code(), Instant.now().getEpochSecond())) {
            throw ApiException.badRequest("bad_code", "That code does not match — check the app's clock");
        }
        users.storeTotpSecret(operator.id(), cipher.encrypt(request.secret().trim()));
    }

    @PostMapping("/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(Authentication authentication, @RequestBody DeactivateRequest request) {
        DbUserDetailsService.StoredOperator operator = requireOperator(authentication);
        if (operator.totpSecret() == null) {
            throw ApiException.notFound("totp_inactive", "This account has no second factor to remove");
        }
        // The password is the only thing that still proves identity when the device that held the code is gone.
        if (request.password() == null || !passwords.matches(request.password(), operator.passwordHash())) {
            throw ApiException.badRequest("password_required", "Deactivating the second factor needs the password");
        }
        users.storeTotpSecret(operator.id(), null);
    }

    private DbUserDetailsService.StoredOperator requireOperator(Authentication authentication) {
        String username = authentication == null ? null : authentication.getName();
        return users.find(username).orElseThrow(() -> ApiException.notFound("unknown_operator",
            "No stored account for this session"));
    }
}
