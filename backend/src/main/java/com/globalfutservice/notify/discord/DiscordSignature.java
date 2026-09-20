package com.globalfutservice.notify.discord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;

/**
 * Proves an interaction actually came from Discord.
 *
 * <p>The endpoint this guards is public and unauthenticated — it has to be, because
 * Discord calls it and has no credential of ours to present. The Ed25519 signature is the
 * only thing separating a real slash command from anybody who has found the URL and would
 * like a ticket opened for them. Discord also refuses to register an endpoint that does
 * not reject a deliberately bad signature, so this is not optional in either direction.
 *
 * <p><b>No new dependency.</b> Ed25519 has been in the JDK since 15. The only fiddly part
 * is that Discord publishes the key as 32 raw hex bytes while {@code KeyFactory} wants a
 * point: the bytes are little-endian y with the x-coordinate's sign in the top bit, so
 * they are reversed, the bit is taken off the front, and what remains is y.
 */
public final class DiscordSignature {

    private static final Logger log = LoggerFactory.getLogger(DiscordSignature.class);

    private DiscordSignature() {
    }

    /**
     * @param publicKeyHex the application's public key, from the Discord developer portal
     * @param signatureHex the {@code X-Signature-Ed25519} header
     * @param timestamp    the {@code X-Signature-Timestamp} header
     * @param body         the raw request body, byte for byte as it arrived — a body that
     *                     has been parsed and re-serialised will not verify
     * @return true only if this is genuinely from Discord
     */
    public static boolean verify(String publicKeyHex, String signatureHex, String timestamp,
                                 String body) {
        if (isBlank(publicKeyHex) || isBlank(signatureHex) || isBlank(timestamp)
                || body == null) {
            return false;
        }
        try {
            byte[] signature = decodeHex(signatureHex);
            if (signature.length != 64) {
                return false;
            }
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey(publicKeyHex));
            verifier.update((timestamp + body).getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signature);
        } catch (Exception e) {
            // Malformed header, malformed key, bad hex: all of them mean "not Discord".
            // Logged at debug because an open endpoint attracts noise and this is the
            // noise being correctly rejected.
            log.debug("Rejected a Discord interaction signature: {}", e.toString());
            return false;
        }
    }

    /** Turns Discord's 32 raw hex bytes into something {@link Signature} will accept. */
    private static PublicKey publicKey(String hex) throws Exception {
        byte[] key = decodeHex(hex);
        if (key.length != 32) {
            throw new IllegalArgumentException("An Ed25519 public key is 32 bytes");
        }
        // Little-endian on the wire, big-endian in BigInteger.
        byte[] reversed = new byte[key.length];
        for (int i = 0; i < key.length; i++) {
            reversed[i] = key[key.length - 1 - i];
        }
        boolean xOdd = (reversed[0] & 0x80) != 0;
        reversed[0] &= (byte) 0x7F;

        EdECPoint point = new EdECPoint(xOdd, new BigInteger(1, reversed));
        return KeyFactory.getInstance("Ed25519")
                .generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, point));
    }

    private static byte[] decodeHex(String hex) {
        String s = hex.trim();
        if (s.length() % 2 != 0) {
            throw new IllegalArgumentException("Odd-length hex");
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Not hex");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
