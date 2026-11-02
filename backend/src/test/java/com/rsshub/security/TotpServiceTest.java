package com.rsshub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The one place in this project where a hand-rolled algorithm has to be provably right, because every other
 * crypto path is delegated to the JDK. The expected codes are RFC 6238 Appendix 1's SHA-1 rows for the reference
 * secret {@code "12345678901234567890"} (that ASCII string is the key), reduced from the RFC's 8 digits to the 6
 * every authenticator app defaults to — the last six of {@code 94287082}, {@code 07081804}, {@code 14050471},
 * {@code 89005924}, {@code 69279037}. Table re-read from rfc-editor.org/rfc/rfc6238.txt on 2026-10-18.
 */
class TotpServiceTest {

    /** RFC 6238 §5.4's reference secret for HMAC-SHA1: the ASCII digits, not their hex. */
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    private static byte[] rfcKey() {
        return "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    }

    @ParameterizedTest
    @CsvSource({
        "59, 287082",
        "1111111109, 081804",
        "1111111111, 050471",
        "1234567890, 005924",
        "2000000000, 279037"
    })
    void matchesTheRfcVectors(long epochSecond, String expected) {
        assertThat(TotpService.codes(rfcKey(), Math.floorDiv(epochSecond, TotpService.periodSeconds())))
            .isEqualTo(expected);
    }

    @Test
    void theBase32OfTheRfcSecretIsTheOneEveryAuthenticatorProduces() {
        assertThat(TotpService.base32(rfcKey())).isEqualTo(RFC_SECRET);
        assertThat(TotpService.fromBase32(RFC_SECRET)).isEqualTo(rfcKey());
    }

    @Test
    void base32ToleratesTheFormattingAnOperatorPastesOutOfAnEmail() {
        assertThat(TotpService.fromBase32("gezd gnbv-gy3tqojq-GEZDGNBVGY3TQOJQ")).isEqualTo(rfcKey());
        assertThatThrownBy(() -> TotpService.fromBase32("0189!")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNewSecretIsTheLengthRfc4226RecommendsAndTwoOfThemDiffer() {
        assertThat(TotpService.newSecret()).hasSize(20);
        assertThat(TotpService.base32(TotpService.newSecret())).isNotEqualTo(TotpService.base32(TotpService.newSecret()));
    }

    @Test
    void theVerifierAcceptsTheNeighbouringStepAndRejectsEverythingElse() {
        long now = Instant.now().getEpochSecond();
        long step = TotpService.periodSeconds();
        String current = TotpService.codes(rfcKey(), Math.floorDiv(now, step));

        assertThat(TotpService.matches(RFC_SECRET, current, now)).isTrue();
        // One step of drift each way is what the phone's clock and this server's can differ by.
        assertThat(TotpService.matches(RFC_SECRET, current, now + step)).isTrue();
        assertThat(TotpService.matches(RFC_SECRET, current, now - step)).isTrue();
        // Two steps out is a code from a different window, and accepting it would double the guessing odds.
        assertThat(TotpService.matches(RFC_SECRET, current, now + 2 * step)).isFalse();
    }

    @Test
    void aCodeIsOnlyEverSixDigitsAndGarbageNeverMatches() {
        long now = Instant.now().getEpochSecond();
        String current = TotpService.codes(rfcKey(), Math.floorDiv(now, TotpService.periodSeconds()));

        assertThat(TotpService.matches(RFC_SECRET, " " + current + " ", now)).isTrue();
        assertThat(TotpService.matches(RFC_SECRET, current.substring(0, 5), now)).isFalse();
        assertThat(TotpService.matches(RFC_SECRET, "abcdef", now)).isFalse();
        assertThat(TotpService.matches(RFC_SECRET, null, now)).isFalse();
        // A stored value that is not base32 must fail closed rather than throw the login page.
        assertThat(TotpService.matches("not-a-secret", current, now)).isFalse();
    }

    @Test
    void theEnrolmentUriCarriesTheParametersAnAppNeedsToRenderTheSameCode() {
        String uri = TotpService.otpauthUri("rss-hub", "admin", RFC_SECRET);

        assertThat(uri).startsWith("otpauth://totp/rss-hub:admin?secret=" + RFC_SECRET)
            .contains("issuer=rss-hub", "algorithm=SHA1", "digits=6", "period=30");
    }
}
