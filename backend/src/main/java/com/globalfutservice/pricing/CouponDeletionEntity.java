package com.globalfutservice.pricing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A coupon an admin deleted: which, by whom, when, and what became of it. Kept for one
 * deleted outright as well, whose own row is gone.
 */
@Entity
@Table(name = "coupon_deletion")
public class CouponDeletionEntity {

    /** What deleting did to the coupon. */
    public enum Outcome {
        /** No order had used it: deleted outright. */
        REMOVED,
        /** Orders had used it: kept, marked deleted, and never applied again. */
        HIDDEN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coupon_id", nullable = false, updatable = false)
    private Long couponId;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(name = "discount_bps", nullable = false, updatable = false)
    private int discountBps;

    @Column(name = "redeemed_count", nullable = false, updatable = false)
    private int redeemedCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Outcome outcome;

    @Column(name = "deleted_by", updatable = false)
    private Long deletedBy;

    @Column(name = "deleted_at", nullable = false, updatable = false)
    private Instant deletedAt;

    protected CouponDeletionEntity() {
    }

    public CouponDeletionEntity(CouponEntity coupon, Outcome outcome, Long deletedBy, Instant deletedAt) {
        this.couponId = coupon.getId();
        this.code = coupon.getCode();
        this.discountBps = coupon.getDiscountBps();
        this.redeemedCount = coupon.getRedeemedCount();
        this.outcome = outcome;
        this.deletedBy = deletedBy;
        this.deletedAt = deletedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getCouponId() {
        return couponId;
    }

    public String getCode() {
        return code;
    }

    public int getDiscountBps() {
        return discountBps;
    }

    public int getRedeemedCount() {
        return redeemedCount;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public Long getDeletedBy() {
        return deletedBy;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
