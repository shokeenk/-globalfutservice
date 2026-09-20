package com.globalfutservice.notify.discord;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The only thing standing in front of a public, unauthenticated endpoint.
 *
 * <p>Signed with a real Ed25519 key generated here rather than against a recorded
 * fixture, because the interesting property is not "this one message verifies" — it is
 * that a message Discord did not sign does not, however plausible it looks.
 *
 * <p>Discord itself tests this on registration: it sends a deliberately invalid signature
 * and refuses the endpoint if it gets a 2xx back. A regression here would not be a subtle
 * bug, it would be an endpoint anybody could drive.
 */
class DiscordSignatureTest {

    private static KeyPair keys;
    private static String publicKeyHex;

    @BeforeAll
    static void generateKey() throws Exception {
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        publicKeyHex = encodePublicKey((EdECPublicKey) keys.getPublic());
    }

    /** The 32 raw bytes, in the form Discord publishes: little-endian y, sign bit on top. */
    private static String encodePublicKey(EdECPublicKey key) {
        byte[] y = key.getPoint().getY().toByteArray();
        byte[] little = new byte[32];
        // toByteArray is big-endian and may carry a leading zero; copy what fits, reversed.
        int copy = Math.min(y.length, 32);
        for (int i = 0; i < copy; i++) {
            little[i] = y[y.length - 1 - i];
        }
        if (key.getPoint().isXOdd()) {
            little[31] |= (byte) 0x80;
        }
        return HexFormat.of().formatHex(little);
    }

    private static String sign(String timestamp, String body) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update((timestamp + body).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(signer.sign());
    }

    @Test
    @DisplayName("a genuinely signed interaction verifies")
    void realSignaturePasses() throws Exception {
        String body = "{\"type\":1}";
        String timestamp = "1758000000";

        assertThat(DiscordSignature.verify(publicKeyHex, sign(timestamp, body), timestamp, body))
                .isTrue();
    }

    @Test
    @DisplayName("a body edited after signing does not")
    void tamperedBodyFails() throws Exception {
        String timestamp = "1758000000";
        String signature = sign(timestamp, "{\"type\":1}");

        assertThat(DiscordSignature.verify(publicKeyHex, signature, timestamp, "{\"type\":2}"))
                .isFalse();
    }

    @Test
    @DisplayName("replaying a signature under a different timestamp does not")
    void tamperedTimestampFails() throws Exception {
        String body = "{\"type\":1}";
        String signature = sign("1758000000", body);

        assertThat(DiscordSignature.verify(publicKeyHex, signature, "1758000001", body))
                .isFalse();
    }

    @Test
    @DisplayName("a signature from the wrong key does not")
    void otherKeyFails() throws Exception {
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(other.getPrivate());
        signer.update(("1758000000" + "{\"type\":1}").getBytes(StandardCharsets.UTF_8));
        String foreign = HexFormat.of().formatHex(signer.sign());

        assertThat(DiscordSignature.verify(publicKeyHex, foreign, "1758000000", "{\"type\":1}"))
                .isFalse();
    }

    @Test
    @DisplayName("junk in the signature header is refused, not thrown")
    void malformedInputIsRefused() {
        // This is what Discord's own registration probe looks like, and what arrives when
        // somebody finds the URL. It must be false, never an exception, never a 500.
        assertThat(DiscordSignature.verify(publicKeyHex, "not-hex", "1758000000", "{}")).isFalse();
        assertThat(DiscordSignature.verify(publicKeyHex, "abcd", "1758000000", "{}")).isFalse();
        assertThat(DiscordSignature.verify(publicKeyHex, null, "1758000000", "{}")).isFalse();
        assertThat(DiscordSignature.verify(publicKeyHex, "ab".repeat(64), null, "{}")).isFalse();
    }

    @Test
    @DisplayName("an unconfigured public key refuses everything")
    void missingKeyRefuses() throws Exception {
        String body = "{\"type\":1}";
        String signature = sign("1758000000", body);

        // Rather than accepting everything, which is the dangerous way to fail.
        assertThat(DiscordSignature.verify(null, signature, "1758000000", body)).isFalse();
        assertThat(DiscordSignature.verify("", signature, "1758000000", body)).isFalse();
    }
}
