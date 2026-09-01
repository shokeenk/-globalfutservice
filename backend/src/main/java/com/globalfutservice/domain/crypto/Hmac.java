package com.globalfutservice.domain.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/**
 * HMAC-SHA256 plus a constant-time comparison, used by both the quote signer and the
 * Razorpay webhook verifier.
 *
 * <p>The comparison matters as much as the MAC. {@code String.equals} returns as soon as
 * two bytes differ, which leaks — over enough requests — how much of a forged signature
 * prefix was correct, and that is enough to reconstruct a valid one byte by byte.
 * {@link MessageDigest#isEqual} is the constant-time primitive the JDK already ships.
 */
public final class Hmac {

    private static final String ALGO = "HmacSHA256";

    private Hmac() {
    }

    public static byte[] sha256(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(key, ALGO));
            return mac.doFinal(message);
        } catch (Exception e) {
            // Both branches are "the JVM lacks HmacSHA256", which cannot happen on a
            // supported runtime. Fail loudly rather than degrade to an unsigned path.
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    public static String hexSha256(String key, String message) {
        return HexFormat.of().formatHex(
                sha256(key.getBytes(StandardCharsets.UTF_8), message.getBytes(StandardCharsets.UTF_8)));
    }

    public static String base64UrlSha256(String key, String message) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                sha256(key.getBytes(StandardCharsets.UTF_8), message.getBytes(StandardCharsets.UTF_8)));
    }

    /** Constant-time equality. Never replace this with {@code equals}. */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
