package com.globalfutservice.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One issued refresh token, stored as a SHA-256 hash of the value handed to the browser.
 *
 * <p>Storing the raw token would mean a database dump is a set of live sessions. Storing
 * the hash means a dump is useless without the tokens themselves.
 *
 * <p>{@code familyId} implements rotation-with-reuse-detection: each refresh issues a new
 * token in the same family and marks the old one used. If a <i>used</i> token is ever
 * presented again, the token was stolen — either by the attacker or by the legitimate
 * user, and there is no way to tell which — so the whole family is revoked and both
 * parties are forced to sign in again.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "family_id", nullable = false)
    private String familyId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "user_agent")
    private String userAgent;

    /** Hashed, not raw: an IP is personal data and this table does not need it in clear. */
    @Column(name = "ip_hash")
    private String ipHash;

    protected RefreshTokenEntity() {
    }

    public RefreshTokenEntity(Long accountId, String tokenHash, String familyId, Instant expiresAt) {
        this.accountId = accountId;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(Instant now) {
        return usedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getFamilyId() {
        return familyId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(Instant usedAt) {
        this.usedAt = usedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public void setIpHash(String ipHash) {
        this.ipHash = ipHash;
    }
}
