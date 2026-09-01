package com.globalfutservice.affiliate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Locale;

/**
 * A creator who sends traffic in exchange for commission.
 *
 * <p>This is the growth loop the business actually runs on. The client is himself a
 * streamer, so the first codes are for the creators he recruits — which is why the
 * commission rate and the first-order discount are per-affiliate columns rather than
 * global constants: he will negotiate them individually.
 */
@Entity
@Table(name = "affiliate")
public class AffiliateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** As the creator typed it, for display on their dashboard. */
    @Column(nullable = false)
    private String code;

    /** Upper-cased; carries the uniqueness constraint and all lookups. */
    @Column(name = "code_normalised", nullable = false)
    private String codeNormalised;

    @Column(name = "display_name")
    private String displayName;

    private String channels;

    @Column(name = "commission_bps", nullable = false)
    private int commissionBps = 1000;

    @Column(name = "first_order_discount_bps", nullable = false)
    private int firstOrderDiscountBps = 1300;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AffiliateStatus status = AffiliateStatus.PENDING;

    @Column(name = "payout_details")
    private String payoutDetails;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "approved_at")
    private Instant approvedAt;

    protected AffiliateEntity() {
    }

    public AffiliateEntity(Long accountId, String code, String displayName, String channels) {
        this.accountId = accountId;
        setCode(code);
        this.displayName = displayName;
        this.channels = channels;
    }

    public static String normalise(String code) {
        return code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }

    public void setCode(String code) {
        this.code = code == null ? null : code.trim();
        this.codeNormalised = normalise(code);
    }

    public boolean isActive() {
        return status == AffiliateStatus.ACTIVE;
    }

    public void approve(Instant at) {
        this.status = AffiliateStatus.ACTIVE;
        this.approvedAt = at;
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getCode() {
        return code;
    }

    public String getCodeNormalised() {
        return codeNormalised;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getChannels() {
        return channels;
    }

    public int getCommissionBps() {
        return commissionBps;
    }

    public void setCommissionBps(int commissionBps) {
        this.commissionBps = commissionBps;
    }

    public int getFirstOrderDiscountBps() {
        return firstOrderDiscountBps;
    }

    public void setFirstOrderDiscountBps(int firstOrderDiscountBps) {
        this.firstOrderDiscountBps = firstOrderDiscountBps;
    }

    public AffiliateStatus getStatus() {
        return status;
    }

    public void setStatus(AffiliateStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }
}
