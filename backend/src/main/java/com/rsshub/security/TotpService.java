package com.rsshub.security;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 TOTP, written against the RFC rather than a library: the algorithm is ~40 lines of HMAC-SHA1 plus
 * dynamic truncation (§5.4 of RFC 6238 citing RFC 4226 §5.3), and pulling a dependency in to do it would add a
 * supply-chain surface to the one code path that decides whether an intruder gets in.
 *
 * <p>Parameters are the ones every authenticator app defaults to — SHA1, 6 digits, 30-second step — because a
 * non-standard period or digit count is the reason people cannot scan a QR code, not a security win.
 */
public final class TotpService {

    private static final int DIGITS = 6;
    private static final int PERIOD_SECONDS = 30;
    /** One step of slack each way: the operator's phone and this server do not share a clock. */
    private static final int WINDOW_STEPS = 1;
    private static final String ALGORITHM = "HmacSHA1";
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final int[] BASE32_LOOKUP = buildLookup();

    /** How many attempts a code gets before the enrolment token is thrown away. */
    public static int digits() {
        return DIGITS;
    }

    public static int periodSeconds() {
        return PERIOD_SECONDS;
    }

    /** 20 bytes of randomness: RFC 4226 recommends at least 128 bits, and every app accepts 160. */
    public static byte[] newSecret() {
        byte[] secret = new byte[20];
        new java.security.SecureRandom().nextBytes(secret);
        return secret;
    }

    public static String base32(byte[] secret) {
        StringBuilder out = new StringBuilder((secret.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : secret) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                out.append(BASE32[(buffer >> (bitsLeft - 5)) & 0x1f]);
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            out.append(BASE32[(buffer << (5 - bitsLeft)) & 0x1f]);
        }
        return out.toString();
    }

    public static byte[] fromBase32(String encoded) {
        String cleaned = encoded.trim().toUpperCase(Locale.ROOT).replace(" ", "").replace("-", "");
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("empty secret");
        }
        byte[] out = new byte[cleaned.length() * 5 / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int index = 0;
        for (int position = 0; position < cleaned.length(); position++) {
            int value = cleaned.charAt(position);
            if (value >= BASE32_LOOKUP.length || BASE32_LOOKUP[value] < 0) {
                throw new IllegalArgumentException("not base32: " + cleaned.charAt(position));
            }
            buffer = (buffer << 5) | BASE32_LOOKUP[value];
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[index++] = (byte) (buffer >> (bitsLeft - 8));
                bitsLeft -= 8;
            }
        }
        return out;
    }

    /** The URI an authenticator app reads out of a QR code; {@code issuer} is what labels it in the app. */
    public static String otpauthUri(String issuer, String accountName, String base32Secret) {
        return "otpauth://totp/" + urlEncode(issuer) + ":" + urlEncode(accountName)
            + "?secret=" + base32Secret + "&issuer=" + urlEncode(issuer)
            + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    /** Accepted if it matches the current step or either neighbour; comparison is constant time. */
    public static boolean matches(String base32Secret, String code, long epochSecond) {
        if (code == null) {
            return false;
        }
        String normalised = code.replaceAll("[\\s-]", "");
        if (!normalised.matches("\\d{6}")) {
            return false;
        }
        byte[] secret;
        try {
            secret = fromBase32(base32Secret);
        } catch (IllegalArgumentException e) {
            return false;
        }
        long counter = Math.floorDiv(epochSecond, PERIOD_SECONDS);
        for (int step = -WINDOW_STEPS; step <= WINDOW_STEPS; step++) {
            if (MessageDigest.isEqual(codes(secret, counter + step).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                normalised.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }

    static String codes(byte[] secret, long counter) {
        byte[] hash = hmac(secret, ByteBuffer.allocate(8).putLong(counter).array());
        // RFC 4226 §5.3 dynamic truncation: the low 4 bits of the last byte pick the offset.
        int offset = hash[hash.length - 1] & 0xf;
        int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
            | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
        return String.format("%0" + DIGITS + "d", binary % (int) Math.pow(10, DIGITS));
    }

    private static byte[] hmac(byte[] secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(ALGORITHM + " is required by the JDK spec", e);
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int[] buildLookup() {
        int[] lookup = new int[128];
        java.util.Arrays.fill(lookup, -1);
        for (int index = 0; index < BASE32.length; index++) {
            lookup[BASE32[index]] = index;
            if (Character.isLowerCase(BASE32[index])) {
                lookup[Character.toLowerCase(BASE32[index])] = index;
            }
        }
        return lookup;
    }
}
