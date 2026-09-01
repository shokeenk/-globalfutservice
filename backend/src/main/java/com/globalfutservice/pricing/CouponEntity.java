package com.globalfutservice.pricing;

import com.globalfutservice.domain.pricing.Coupon;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A discount code as stored. See {@link Coupon} for the rules it must satisfy. */
@Entity
@Table(name = "coupon")
public class CouponEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(name = "discount_bps", nullable = false)
    private int discountBps;

    private String description;

    @Column(name = "max_redemptions")
    private Integer maxRedemptions;

    @Column(name = "redeemed_count", nullable = false)
    private int redeemedCount = 0;

    @Column(name = "max_per_account", nullable = false)
    private int maxPerAccount = 1;

    @Column(name = "min_order_minor", nullable = false)
    private long minOrderMinor = 0L;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected CouponEntity() {
    }

    public CouponEntity(String code, int discountBps, Long createdBy) {
        // Round-trips through the domain record, so every rule it enforces — the 20%
        // ceiling, the character set, the length — applies to anything built here. The
        // entity has no separate idea of what a valid coupon is.
        Coupon validated = new Coupon(code, discountBps, 0L, null);
        this.code = validated.code();
        this.discountBps = validated.discountBps();
        this.createdBy = createdBy;
    }

    /** The framework-free value the pricing engine reasons about. */
    public Coupon toDomain() {
        return new Coupon(code, discountBps, minOrderMinor, expiresAt);
    }

    /**
     * Whether this coupon is usable at all right now, ignoring per-account limits.
     *
     * <p>Deliberately does not consider the redemption count as a reason to *apply* it —
     * that check has to happen atomically at redemption time, not here, because between
     * this returning true and an order being created somebody else may take the last one.
     */
    public boolean isLiveAt(Instant now) {
        return active && (expiresAt == null || now.isBefore(expiresAt));
    }

    public boolean isExhausted() {
        return maxRedemptions != null && redeemedCount >= maxRedemptions;
    }

    /** Remaining redemptions, or null when unlimited. */
    public Integer remaining() {
        return maxRedemptions == null ? null : Math.max(0, maxRedemptions - redeemedCount);
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public int getDiscountBps() {
        return discountBps;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
        touch();
    }

    public Integer getMaxRedemptions() {
        return maxRedemptions;
    }

    public void setMaxRedemptions(Integer maxRedemptions) {
        this.maxRedemptions = maxRedemptions;
        touch();
    }

    public int getRedeemedCount() {
        return redeemedCount;
    }

    public int getMaxPerAccount() {
        return maxPerAccount;
    }

    public void setMaxPerAccount(int maxPerAccount) {
        this.maxPerAccount = maxPerAccount;
        touch();
    }

    public long getMinOrderMinor() {
        return minOrderMinor;
    }

    public void setMinOrderMinor(long minOrderMinor) {
        this.minOrderMinor = minOrderMinor;
        touch();
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
        touch();
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
        touch();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
