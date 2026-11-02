package com.rsshub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rsshub.common.ApiException;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * {@code app_user.totp_secret} is the only secret this project encrypts itself, so the two properties that matter
 * are tested directly: a value that does not decrypt is an error, never a silent "no second factor", and a stored
 * value never reveals itself under a different key.
 */
class SecretCipherTest {

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static SecretCipher cipher(String key) {
        return new SecretCipher(new SecurityProperties(key));
    }

    @Test
    void aSecretSurvivesTheRoundTrip() {
        SecretCipher cipher = cipher(randomKey());
        String stored = cipher.encrypt("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");

        assertThat(stored).startsWith("v1:").isNotEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(cipher.decrypt(stored)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
    }

    @Test
    void twoEncryptionsOfTheSameSecretDifferBecauseTheIvIsFresh() {
        SecretCipher cipher = cipher(randomKey());

        assertThat(cipher.encrypt("AAAA")).isNotEqualTo(cipher.encrypt("AAAA"));
    }

    @Test
    void anotherKeyCannotReadIt() {
        String stored = cipher(randomKey()).encrypt("GEZDGNBVGY3TQOJQ");

        assertThatThrownBy(() -> cipher(randomKey()).decrypt(stored))
            .isInstanceOfSatisfying(ApiException.class, (e) -> {
                assertThat(e.code()).isEqualTo("secret_unreadable");
                // The message must not quote ciphertext: it is written to a log and shown to whoever asks.
                assertThat(e.getMessage()).doesNotContain(stored);
            });
    }

    @Test
    void atamperedCiphertextIsRefusedNotDecodedIntoGarbage() {
        SecretCipher cipher = cipher(randomKey());
        String stored = cipher.encrypt("GEZDGNBVGY3TQOJQ");
        String flipped = stored.substring(0, stored.length() - 2)
            + (stored.endsWith("AA") ? "BB" : "AA");

        // GCM's tag covers the whole value, so a one-character edit is a decrypt failure rather than a wrong answer.
        assertThatThrownBy(() -> cipher.decrypt(flipped)).isInstanceOf(ApiException.class);
    }

    @Test
    void aValueThatPredatesTheFormatIsNamedInsteadOfGuessedAt() {
        assertThatThrownBy(() -> cipher(randomKey()).decrypt("GEZDGNBVGY3TQOJQ"))
            .isInstanceOfSatisfying(ApiException.class, (e) -> assertThat(e.code()).isEqualTo("secret_format"));
    }

    @Test
    void absentSecretsAreNotAnErrorBecauseMostInstallsHaveNone() {
        SecretCipher cipher = cipher(randomKey());

        assertThat(cipher.decrypt(null)).isNull();
        assertThat(cipher.decrypt("  ")).isNull();
    }

    @Test
    void withoutAKeyTheFeatureIsOffAndWritingRefusesRatherThanStoringPlaintext() {
        SecretCipher cipher = cipher("");

        assertThat(cipher.enabled()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("GEZDGNBVGY3TQOJQ"))
            .isInstanceOfSatisfying(ApiException.class, (e) -> {
                assertThat(e.code()).isEqualTo("secret_key_missing");
                assertThat(e.status().is4xxClientError()).isTrue();
            });
    }

    @Test
    void aWrongSizedOrNonBase64KeyFailsAtStartupNotAtFirstEnrolment() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> cipher(tooShort)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> cipher("not base64 !!")).isInstanceOf(IllegalStateException.class);
    }
}
