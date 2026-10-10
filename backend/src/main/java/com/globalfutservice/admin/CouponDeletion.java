package com.globalfutservice.admin;

import java.time.Clock;
import java.time.Instant;

import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.pricing.CouponDeletionEntity;
import com.globalfutservice.pricing.CouponDeletionRepository;
import com.globalfutservice.pricing.CouponEntity;
import com.globalfutservice.pricing.CouponRedemptionRepository;
import com.globalfutservice.pricing.CouponRepository;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deleting a coupon from the admin's list.
 *
 * <p>One no order has used is deleted outright. One that orders have used is kept and marked
 * deleted: those orders, their redemptions and their frozen price breakdowns are untouched,
 * and an unpaid one keeps the discount it was placed with. Either way the coupon is gone from
 * the list, its code is not valid at checkout, and the code is free for a new coupon.
 *
 * <p>"Used" means a redemption is held for it, or an order placed since it was created
 * carries its code -- an abandoned or refunded order hands its redemption back but still
 * shows the discount it had. Every deletion is recorded: who, when, and which way.
 */
@Service
public class CouponDeletion {

    private static final Logger log = LoggerFactory.getLogger(CouponDeletion.class);

    /** What happened, for the admin's confirmation. */
    public record Deleted(Long id, String code, CouponDeletionEntity.Outcome outcome) {
    }

    private final CouponRepository coupons;
    private final CouponRedemptionRepository redemptions;
    private final CouponDeletionRepository deletions;
    private final OrderRepository orders;
    private final Clock clock;

    public CouponDeletion(CouponRepository coupons, CouponRedemptionRepository redemptions,
                          CouponDeletionRepository deletions, OrderRepository orders, Clock clock) {
        this.coupons = coupons;
        this.redemptions = redemptions;
        this.deletions = deletions;
        this.orders = orders;
        this.clock = clock;
    }

    /**
     * Deletes the coupon, as {@code adminId}. Locked while it is decided, so a checkout
     * claiming it at the same moment either lands first -- and the coupon is kept for that
     * order -- or finds it gone.
     */
    @Transactional
    public Deleted delete(Long couponId, Long adminId) {
        CouponEntity coupon = coupons.findForUpdate(couponId).filter(c -> !c.isDeleted())
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such coupon."));
        Instant now = clock.instant();
        boolean used = redemptions.existsByCouponId(coupon.getId())
                || orders.existsByCouponCodeAndCreatedAtGreaterThanEqual(coupon.getCode(), coupon.getCreatedAt());
        CouponDeletionEntity.Outcome outcome = used
                ? CouponDeletionEntity.Outcome.HIDDEN : CouponDeletionEntity.Outcome.REMOVED;
        deletions.save(new CouponDeletionEntity(coupon, outcome, adminId, now));
        if (used) {
            coupon.markDeleted(adminId, now);
            coupons.save(coupon);
        } else {
            coupons.delete(coupon);
        }
        coupons.flush();
        log.info("Coupon {} ({}) deleted by account {}: {}", coupon.getCode(), coupon.getId(), adminId,
                used ? "used by orders, kept and hidden" : "never used, removed");
        return new Deleted(coupon.getId(), coupon.getCode(), outcome);
    }
}
