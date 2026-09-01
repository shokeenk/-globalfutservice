package com.globalfutservice.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Locale;

/**
 * A person who can sign in — customer or staff.
 *
 * <p>{@code email} keeps the address exactly as typed, for display and for email
 * delivery. {@code emailNormalised} is the lower-cased, trimmed form that carries the
 * uniqueness constraint, so {@code Kunal@Example.com} cannot register a second account
 * alongside {@code kunal@example.com}.
 */
@Entity
@Table(name = "account")
public class AccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false)
    private String publicId;

    @Column(nullable = false)
    private String email;

    @Column(name = "email_normalised", nullable = false)
    private String emailNormalised;

    private String phone;

    @Column(name = "display_name")
    private String displayName;

    /**
     * BCrypt hash, or null for a federated-only account.
     * Never serialised: see {@code AccountResponse}, which is a separate DTO precisely so
     * that no entity field can leak into a response by accident.
     */
    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountRole role = AccountRole.CUSTOMER;

    @Column(name = "oauth_provider")
    private String oauthProvider;

    @Column(name = "oauth_subject")
    private String oauthSubject;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    /** Creator code this customer arrived through; drives affiliate attribution. */
    @Column(name = "referred_by_code")
    private String referredByCode;

    /** Set the first time an order reaches COMPLETED. Gates the first-order discount. */
    @Column(name = "first_completed_at")
    private Instant firstCompletedAt;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected AccountEntity() {
    }

    public AccountEntity(String publicId, String email, String passwordHash, AccountRole role) {
        this.publicId = publicId;
        setEmail(email);
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public static String normalise(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public boolean isActive() {
        return disabledAt == null;
    }

    public boolean isFirstOrder() {
        return firstCompletedAt == null;
    }

    public void setEmail(String email) {
        this.email = email;
        this.emailNormalised = normalise(email);
    }

    // --- accessors -----------------------------------------------------------

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public String getEmail() {
        return email;
    }

    public String getEmailNormalised() {
        return emailNormalised;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public AccountRole getRole() {
        return role;
    }

    public void setRole(AccountRole role) {
        this.role = role;
    }

    public String getOauthProvider() {
        return oauthProvider;
    }

    public void setOauthProvider(String oauthProvider) {
        this.oauthProvider = oauthProvider;
    }

    public String getOauthSubject() {
        return oauthSubject;
    }

    public void setOauthSubject(String oauthSubject) {
        this.oauthSubject = oauthSubject;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public void setEmailVerifiedAt(Instant emailVerifiedAt) {
        this.emailVerifiedAt = emailVerifiedAt;
    }

    public String getReferredByCode() {
        return referredByCode;
    }

    public void setReferredByCode(String referredByCode) {
        this.referredByCode = referredByCode;
    }

    public Instant getFirstCompletedAt() {
        return firstCompletedAt;
    }

    public void setFirstCompletedAt(Instant firstCompletedAt) {
        this.firstCompletedAt = firstCompletedAt;
    }

    public int getFailedLoginCount() {
        return failedLoginCount;
    }

    public void setFailedLoginCount(int failedLoginCount) {
        this.failedLoginCount = failedLoginCount;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public Instant getDisabledAt() {
        return disabledAt;
    }

    public void setDisabledAt(Instant disabledAt) {
        this.disabledAt = disabledAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Never let an entity holding a password hash render itself into a log line.
     */
    @Override
    public String toString() {
        return "Account[" + publicId + ", role=" + role + "]";
    }
}
