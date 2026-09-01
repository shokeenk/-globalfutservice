package com.globalfutservice.domain.crypto;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Unguessable public identifiers.
 *
 * <p>Database primary keys are never shown to a customer. Sequential integers let anyone
 * count the business's daily volume from two order confirmations, and invite walking
 * {@code /orders/1..n} looking for a missing authorisation check.
 */
public final class SecureIds {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    /** Crockford-ish: no I, L, O, U — unambiguous when read aloud to support. */
    private static final char[] REF_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private SecureIds() {
    }

    public static String token(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return ENCODER.encodeToString(buf);
    }

    public static String quoteId() {
        return "q_" + token(18);
    }

    /**
     * Customer-facing order reference, e.g. {@code GFS-26-7K2M9QX4}.
     * Random rather than sequential, and safe to read over the phone.
     */
    public static String orderRef(String seasonYear) {
        StringBuilder sb = new StringBuilder("GFS-").append(seasonYear).append('-');
        byte[] buf = new byte[8];
        RANDOM.nextBytes(buf);
        for (byte b : buf) {
            sb.append(REF_ALPHABET[(b & 0xFF) % REF_ALPHABET.length]);
        }
        return sb.toString();
    }

    /**
     * Customer-facing coaching-session reference, e.g. {@code GFS-C-7K2M9QX4}.
     *
     * <p>Its own prefix rather than sharing the order alphabet soup: a customer with both
     * an order and four sessions is going to quote one of them in a support chat, and
     * "which of these is which" should not be a question anybody has to ask.
     */
    public static String sessionRef() {
        StringBuilder sb = new StringBuilder("GFS-C-");
        byte[] buf = new byte[8];
        RANDOM.nextBytes(buf);
        for (byte b : buf) {
            sb.append(REF_ALPHABET[(b & 0xFF) % REF_ALPHABET.length]);
        }
        return sb.toString();
    }

    /** Affiliate codes are user-chosen; this is the fallback suggestion. */
    public static String affiliateCode(String seed) {
        String cleaned = seed == null ? "" : seed.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        String prefix = cleaned.length() > 8 ? cleaned.substring(0, 8) : cleaned;
        StringBuilder sb = new StringBuilder(prefix);
        byte[] buf = new byte[3];
        RANDOM.nextBytes(buf);
        for (byte b : buf) {
            sb.append(REF_ALPHABET[(b & 0xFF) % REF_ALPHABET.length]);
        }
        return sb.toString();
    }
}
