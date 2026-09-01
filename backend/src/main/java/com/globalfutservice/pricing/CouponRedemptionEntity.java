package com.globalfutservice.pricing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One order's use of one coupon.
 *
 * <p>This table is the record; {@code coupon.redeemed_count} is a denormalised total kept
 * for the limit check and rebuildable from these rows. The discount is frozen here because
 * a coupon's percentage can be edited later and what a given customer actually received
 * must not move when it is.
 */
@Entity
@Table(name = "coupon_redemption")
public class CouponRedemptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coupon_id", nullable = false)
    private Long couponId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "discount_bps", nullable = false)
    private int discountBps;

    @Column(name = "discount_minor", nullable = false)
    private long discountMinor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected CouponRedemptionEntity() {
    }

    public CouponRedemptionEntity(Long couponId, Long orderId, Long accountId,
                                  int discountBps, long discountMinor) {
        this.couponId = couponId;
        this.orderId = orderId;
        this.accountId = accountId;
        this.discountBps = discountBps;
        this.discountMinor = discountMinor;
    }

    public Long getId() {
        return id;
    }

    public Long getCouponId() {
        return couponId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public int getDiscountBps() {
        return discountBps;
    }

    public long getDiscountMinor() {
        return discountMinor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
