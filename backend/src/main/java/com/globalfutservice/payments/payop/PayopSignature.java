package com.globalfutservice.payments.payop;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The signature Payop requires on every invoice it creates.
 *
 * <p>As Payop documents it (0.Integration/signatureGenerator.md and s2s.md): the SHA-256 of
 * {@code amount:currency:orderId:secretKey}, joined with colons in that order, as lower-case
 * hex. A plain hash, not an HMAC. {@code amount} must be byte-for-byte the string sent as
 * {@code order.amount}: "2" and "2.00" sign differently, and Payop refuses the mismatch.
 *
 * <p>The method is described the same way in all three places Payop documents it. Its two
 * "verified signature examples" (s2s.md) do not match that method, or any variant of it we
 * tried, so they were not used; the first supervised payment confirms the method, since
 * Payop answers "Wrong signature" to a wrong one.
 */
public final class PayopSignature {

    private PayopSignature() {
    }

    public static String of(String amount, String currency, String orderId, String secretKey) {
        String text = amount + ":" + currency + ":" + orderId + ":" + secretKey;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
