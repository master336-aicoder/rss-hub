package com.rsshub.notify;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The two HMAC shapes this package needs, both keyed by a secret from the environment (03 §8.3's signature header
 * and 03 §8.3's DingTalk 加签).
 *
 * <p>Hex for the webhook header because that is the convention the {@code sha256=<digest>} prefix carries; base64
 * for DingTalk because that is what its own documented procedure says to do before url-encoding the result.
 */
final class Signer {

    private Signer() {
    }

    static String hmacSha256Hex(String secret, String data) {
        return toHex(hmac(secret, data));
    }

    static String hmacSha256Base64(String secret, String data) {
        return Base64.getEncoder().encodeToString(hmac(secret, data));
    }

    static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static byte[] hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            // Unreachable on any JVM that runs the rest of this application: HmacSHA256 is a required algorithm.
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private static String toHex(byte[] digest) {
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte cell : digest) {
            hex.append(Character.forDigit((cell >> 4) & 0xF, 16)).append(Character.forDigit(cell & 0xF, 16));
        }
        return hex.toString();
    }
}
