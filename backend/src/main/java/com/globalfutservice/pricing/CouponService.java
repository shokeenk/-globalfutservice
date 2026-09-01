package com.globalfutservice.pricing;

import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.pricing.Coupon;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Coupon validation and redemption.
 *
 * <p>Validation happens twice, and the two are not the same check.
 *
 * <ol>
 *   <li><b>At quote time</b> ({@link #resolve}) the code is looked up and priced. This is
 *       advisory: it tells the customer what they would pay. It deliberately does not
 *       reserve anything, because a quote is a price, not a booking, and holding a
 *       redemption for every slider drag would exhaust a campaign in an afternoon.
 *   <li><b>At order time</b> ({@link #redeem}) the redemption is claimed atomically. This
 *       is the authoritative check, and it can legitimately fail after a successful quote
 *       — somebody else took the last one in between. That is not an error in the system;
 *       it is the system working.
 * </ol>
 *
 * <p>The gap between the two is unavoidable in any design that does not lock a coupon for
 * the duration of a browsing session. What matters is that the second check is the one
 * that decides, and that it cannot be raced.
 */
@Service
public class CouponService {

    private static final Logger log = LoggerFactory.getLogger(CouponService.class);

    private final CouponRepository coupons;
    private final CouponRedemptionRepository redemptions;
    private final Clock clock;

    public CouponService(CouponRepository coupons,
                         CouponRedemptionRepository redemptions,
                         Clock clock) {
        this.coupons = coupons;
        this.redemptions = redemptions;
        this.clock = clock;
    }

    /** What a coupon is worth to this customer on an order of this size, or empty. */
    public record Resolved(Long couponId, String code, int discountBps) {
    }

    /**
     * Price a coupon for a quote.
     *
     * <p>Returns empty rather than throwing for an unknown or spent code. A quote is
     * recomputed on every slider drag, and turning a stale coupon into an error would
     * mean the price disappears rather than simply losing its discount. The customer is
     * told separately — see {@link #explain}.
     */
    @Transactional(readOnly = true)
    public Optional<Resolved> resolve(String rawCode, Long accountId, Money subtotal) {
        if (rawCode == null || rawCode.isBlank()) {
            return Optional.empty();
        }
        return coupons.findByCode(Coupon.normalise(rawCode))
                .filter(c -> usableBy(c, accountId, subtotal).isEmpty())
                .map(c -> new Resolved(c.getId(), c.getCode(), c.getDiscountBps()));
    }

    /**
     * Why a coupon does not apply, in words a customer can act on, or empty when it does.
     *
     * <p>Separate from {@link #resolve} so the quote endpoint can price without a code and
     * still explain the code. The messages name the actual reason — a coupon that silently
     * does nothing generates a support ticket every single time.
     */
    @Transactional(readOnly = true)
    public Optional<String> explain(String rawCode, Long accountId, Money subtotal) {
        if (rawCode == null || rawCode.isBlank()) {
            return Optional.empty();
        }
        Optional<CouponEntity> found = coupons.findByCode(Coupon.normalise(rawCode));
        if (found.isEmpty()) {
            return Optional.of("That code is not valid.");
        }
        return usableBy(found.get(), accountId, subtotal);
    }

    /** The single place that decides whether a coupon applies. Empty means it does. */
    private Optional<String> usableBy(CouponEntity coupon, Long accountId, Money subtotal) {
        Instant now = clock.instant();

        if (!coupon.isActive()) {
            return Optional.of("That code is no longer active.");
        }
        if (!coupon.isLiveAt(now)) {
            return Optional.of("That code has expired.");
        }
        if (coupon.isExhausted()) {
            return Optional.of("That code has been fully claimed.");
        }
        if (subtotal != null && subtotal.minor() < coupon.getMinOrderMinor()) {
            return Optional.of("That code needs a larger order — the minimum is "
                    + Money.ofMinor(coupon.getMinOrderMinor(), subtotal.currency()).format() + ".");
        }
        if (accountId == null && coupon.getMaxPerAccount() > 0) {
            // A per-account limit cannot be enforced against a guest, because there is no
            // account to count against. Rather than pretend, the code simply requires one.
            return Optional.of("Sign in to use that code.");
        }
        if (accountId != null
                && redemptions.countByCouponIdAndAccountId(coupon.getId(), accountId)
                   >= coupon.getMaxPerAccount()) {
            return Optional.of(coupon.getMaxPerAccount() == 1
                    ? "You have already used that code."
                    : "You have used that code the maximum number of times.");
        }
        return Optional.empty();
    }

    /** The id behind a code, for the order path which already knows the code applies. */
    @Transactional(readOnly = true)
    public Optional<Long> resolveIdFor(String rawCode) {
        return coupons.findByCode(Coupon.normalise(rawCode)).map(CouponEntity::getId);
    }

    /**
     * Claim a redemption for an order. Called inside the order-creation transaction.
     *
     * <p>Order of operations matters. The counter is claimed first via a conditional
     * UPDATE — if the coupon is exhausted that returns zero rows and nothing else happens.
     * Only then is the redemption row written, and the unique index on {@code order_id}
     * means a retried request cannot produce a second one.
     *
     * @throws ApiExceptions.ConflictException when the coupon ran out between quote and order
     */
    @Transactional
    public void redeem(Long couponId, String code, Long orderId, Long accountId,
                       int discountBps, long discountMinor) {
        if (couponId == null || orderId == null) {
            return;
        }
        // A retry of the same order must not claim a second redemption. Checked here for
        // a clean exit; enforced by the unique index below regardless.
        if (redemptions.existsByOrderId(orderId)) {
            log.debug("Coupon already redeemed for order {}; skipping", orderId);
            return;
        }

        if (coupons.claimRedemption(couponId) == 0) {
            log.info("Coupon {} was exhausted before order {} could claim it", code, orderId);
            throw new ApiExceptions.ConflictException("coupon_exhausted",
                    "That code was fully claimed while you were checking out. "
                            + "Remove it and your order will go through at the normal price.");
        }

        try {
            redemptions.saveAndFlush(new CouponRedemptionEntity(
                    couponId, orderId, accountId, discountBps, discountMinor));
        } catch (DataIntegrityViolationException duplicate) {
            // Two requests for the same order raced past the check above. Give the
            // claimed redemption back rather than leaving the counter one too high.
            coupons.releaseRedemption(couponId);
            log.debug("Duplicate coupon redemption for order {} collapsed", orderId);
        }
    }

    /**
     * Give a redemption back when an order will never be paid.
     *
     * <p>Called on abandonment and refund. Without it a campaign is slowly consumed by
     * checkouts nobody completed — and the codes that get abandoned most are exactly the
     * ones being shared publicly, so the leak is worst on the campaigns that matter.
     */
    @Transactional
    public void release(Long orderId) {
        redemptions.findByOrderId(orderId).ifPresent(redemption -> {
            coupons.releaseRedemption(redemption.getCouponId());
            redemptions.delete(redemption);
            log.info("Released coupon redemption for order {}", orderId);
        });
    }
}
