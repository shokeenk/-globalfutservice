package com.globalfutservice.loyalty;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.loyalty.LoyaltyTier;
import com.globalfutservice.domain.loyalty.PointsEntryType;
import com.globalfutservice.domain.loyalty.PointsWallet;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The points wallet: 20 points per ₹2,000 spent, one point worth ₹1 at checkout.
 *
 * <p>Two rules keep this honest:
 *
 * <p><b>Points are granted at COMPLETED, never at PAID.</b> The guarantee window has to
 * elapse first. Granting at payment would mean writing clawback logic for every refund
 * and every upheld ban claim; granting after the window means the clawback path exists
 * only for genuine reversals and is almost never exercised.
 *
 * <p><b>Every grant and spend is idempotent per order.</b> A unique index on
 * {@code (order_id, entry_type)} backs the guard below, so a retried job, a duplicated
 * webhook or an operator clicking twice cannot mint points twice — the database refuses
 * even if the application logic is wrong.
 */
@Service
public class LoyaltyService {

    private static final Logger log = LoggerFactory.getLogger(LoyaltyService.class);

    /**
     * Derived from the enum, never spelled out. A new entry type decides its own effect on
     * tier standing by answering {@code countsTowardLifetime()}, and this set follows —
     * rather than someone having to remember there is a second list to update here.
     */
    private static final Set<PointsEntryType> LIFETIME_TYPES = Arrays.stream(PointsEntryType.values())
            .filter(PointsEntryType::countsTowardLifetime)
            .collect(Collectors.toUnmodifiableSet());

    private final PointsLedgerRepository repository;
    private final AppProperties props;
    private final Clock clock;

    public LoyaltyService(PointsLedgerRepository repository, AppProperties props, Clock clock) {
        this.repository = repository;
        this.props = props;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public long balance(Long accountId) {
        return accountId == null ? 0L : repository.balanceOf(accountId);
    }

    @Transactional(readOnly = true)
    public long lifetimeEarned(Long accountId) {
        return accountId == null ? 0L : repository.lifetimeEarned(accountId);
    }

    /**
     * Every point ever earned, never reduced by spending. The number the tier ladder reads.
     *
     * <p>Clamped at zero: a heavily clawed-back account can compute negative, and the
     * honest answer to "which tier is someone with −40 lifetime points in" is BRONZE, not
     * an exception thrown from the middle of a pricing call.
     */
    @Transactional(readOnly = true)
    public long lifetimePoints(Long accountId) {
        if (accountId == null) {
            return 0L;
        }
        return Math.max(0L, repository.sumByEntryTypes(accountId, LIFETIME_TYPES));
    }

    @Transactional(readOnly = true)
    public LoyaltyTier tier(Long accountId) {
        return LoyaltyTier.forLifetimePoints(lifetimePoints(accountId));
    }

    /** Everything the rewards page and the account header need, in one round trip. */
    @Transactional(readOnly = true)
    public LoyaltyStatus status(Long accountId) {
        long lifetime = lifetimePoints(accountId);
        LoyaltyTier tier = LoyaltyTier.forLifetimePoints(lifetime);
        return new LoyaltyStatus(
                tier,
                lifetime,
                balance(accountId),
                tier.pointsToNext(lifetime),
                props.pricing().tierDiscountEnabled() ? tier.discountBps() : 0,
                !hasClaimedToday(accountId),
                props.loyalty().dailyBonusPoints());
    }

    /** The business day, in the business's own zone — not UTC. See {@link #claimDailyBonus}. */
    public LocalDate businessToday() {
        return LocalDate.now(clock.withZone(props.loyalty().bonusZone()));
    }

    @Transactional(readOnly = true)
    public boolean hasClaimedToday(Long accountId) {
        return accountId != null
                && repository.existsByAccountIdAndClaimDate(accountId, businessToday());
    }

    /**
     * The daily check-in grant.
     *
     * <p><b>"Today" is the business's day, not UTC's.</b> With a UTC boundary an Indian
     * customer's streak rolls over at 5:30 in the morning, so checking in at 11pm and again
     * at 6am counts as two days while checking in at 9am two days running counts as one.
     * The zone is configured, so the answer is the same one the customer would give.
     *
     * <p><b>Idempotency is the database's job.</b> A partial unique index on
     * {@code (account_id, claim_date)} is the actual guard; the pre-check below only exists
     * to return a friendly answer in the common case. Two taps on a flaky connection race
     * the read, so the second insert is caught and reported as an ordinary "already
     * claimed" rather than a 500.
     *
     * @return true when this call is what granted the points
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimDailyBonus(Long accountId) {
        if (accountId == null) {
            return false;
        }
        long points = props.loyalty().dailyBonusPoints();
        if (points <= 0) {
            return false;
        }
        LocalDate today = businessToday();
        if (repository.existsByAccountIdAndClaimDate(accountId, today)) {
            return false;
        }
        try {
            repository.saveAndFlush(PointsLedgerEntity.dailyBonus(accountId, points, today));
            return true;
        } catch (DataIntegrityViolationException raced) {
            log.debug("Daily bonus for account {} on {} lost the race; already claimed",
                    accountId, today);
            return false;
        }
    }

    @Transactional(readOnly = true)
    public Page<PointsLedgerEntity> statement(Long accountId, Pageable pageable) {
        return repository.findByAccountIdOrderByCreatedAtDesc(accountId, pageable);
    }

    /** Called once, when an order reaches COMPLETED. */
    @Transactional
    public void award(Long accountId, Long orderId, long points, String orderRef) {
        if (accountId == null || points <= 0) {
            return;
        }
        if (repository.existsByOrderIdAndEntryType(orderId, PointsEntryType.EARNED)) {
            log.debug("Points already awarded for order {}; skipping", orderRef);
            return;
        }
        repository.save(new PointsLedgerEntity(accountId, orderId, PointsEntryType.EARNED,
                PointsWallet.signedAmount(PointsEntryType.EARNED, points),
                "Earned on order " + orderRef, null));
    }

    /**
     * Called when an order is created with points applied.
     *
     * <p>Re-checks the balance inside the transaction rather than trusting the quote.
     * Between minting a quote and accepting it the customer may have spent the same
     * points in another tab — the quote is a price, not a reservation.
     */
    @Transactional
    public void redeem(Long accountId, Long orderId, long points, String orderRef) {
        if (accountId == null || points <= 0) {
            return;
        }
        if (repository.existsByOrderIdAndEntryType(orderId, PointsEntryType.REDEEMED)) {
            return;
        }
        long available = repository.balanceOf(accountId);
        if (available < points) {
            throw new ApiExceptions.ConflictException("insufficient_points",
                    "Your points balance changed. Please refresh and try again.");
        }
        repository.save(new PointsLedgerEntity(accountId, orderId, PointsEntryType.REDEEMED,
                PointsWallet.signedAmount(PointsEntryType.REDEEMED, points),
                "Redeemed on order " + orderRef, null));
    }

    /** Refund path: points spent on a reversed order go back to the customer. */
    @Transactional
    public void reverseRedemption(Long accountId, Long orderId, long points, String orderRef) {
        if (accountId == null || points <= 0) {
            return;
        }
        if (repository.existsByOrderIdAndEntryType(orderId, PointsEntryType.REFUND_REVERSAL)) {
            return;
        }
        repository.save(new PointsLedgerEntity(accountId, orderId, PointsEntryType.REFUND_REVERSAL,
                PointsWallet.signedAmount(PointsEntryType.REFUND_REVERSAL, points),
                "Returned from refunded order " + orderRef, null));
    }

    /** Rare: a completed order later reversed. Kept explicit rather than netting silently. */
    @Transactional
    public void clawback(Long accountId, Long orderId, long points, String orderRef, Long actorId) {
        if (accountId == null || points <= 0) {
            return;
        }
        if (repository.existsByOrderIdAndEntryType(orderId, PointsEntryType.CLAWBACK)) {
            return;
        }
        repository.save(new PointsLedgerEntity(accountId, orderId, PointsEntryType.CLAWBACK,
                PointsWallet.signedAmount(PointsEntryType.CLAWBACK, points),
                "Reversed from order " + orderRef, actorId));
    }

    /**
     * Store credit and goodwill grants.
     *
     * <p>An upheld guarantee claim settled as credit is issued here: the wallet already
     * has a balance, a statement and a redemption path, so store credit rides on it
     * rather than becoming a second currency with its own bugs.
     */
    @Transactional
    public void adjust(Long accountId, Long orderId, long points, String description, Long actorId) {
        if (accountId == null || points == 0) {
            return;
        }
        repository.save(new PointsLedgerEntity(accountId, orderId, PointsEntryType.MANUAL_ADJUSTMENT,
                points, description, actorId));
    }
}
