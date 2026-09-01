package com.globalfutservice.security;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;

/**
 * Access-token minting and verification.
 *
 * <p>Access tokens are short-lived (fifteen minutes) and are held in memory by the
 * frontend, never in {@code localStorage}. The long-lived half of the session is the
 * refresh token, which lives in an {@code HttpOnly; Secure; SameSite=Strict} cookie that
 * JavaScript cannot read — so a cross-site scripting bug on the storefront cannot walk
 * away with a session that outlives the page.
 */
@Service
public class JwtService {

    private static final String ISSUER = "globalfutservices";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_PUBLIC_ID = "pid";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;
    private final AppProperties props;
    private final Clock clock;

    public JwtService(AppProperties props, Clock clock) {
        byte[] secret = props.security().jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "gfs.security.jwt-secret must be at least 32 bytes. "
                            + "Generate one with: openssl rand -base64 48");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.props = props;
        this.clock = clock;
    }

    public String issueAccessToken(AccountEntity account) {
        Instant now = clock.instant();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(account.getId()))
                .claim(CLAIM_PUBLIC_ID, account.getPublicId())
                .claim(CLAIM_ROLE, account.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(props.security().accessTokenTtl())))
                .signWith(key)
                .compact();
    }

    /**
     * @return the caller, or empty for any token that is expired, unsigned, signed with
     *         the wrong key, or otherwise malformed. Callers get no detail about which —
     *         distinguishing "expired" from "forged" in an error message tells an
     *         attacker which half of the problem to work on.
     */
    public Optional<AccountPrincipal> verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return Optional.of(new AccountPrincipal(
                    Long.valueOf(claims.getSubject()),
                    claims.get(CLAIM_PUBLIC_ID, String.class),
                    null,
                    AccountRole.valueOf(claims.get(CLAIM_ROLE, String.class))));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Opaque, high-entropy refresh token. Only its hash is ever persisted. */
    public String newRefreshToken() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    public String hashRefreshToken(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(
                    digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
