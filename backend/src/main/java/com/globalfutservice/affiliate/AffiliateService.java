package com.globalfutservice.affiliate;

import com.globalfutservice.domain.money.Currency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;

/**
 * Creator attribution and commission.
 *
 * <p>Commission accrues on the same event as loyalty points — order COMPLETED, after the
 * guarantee window — for the same reason: paying a creator for an order that is later
 * refunded means clawing money back from someone whose goodwill the business depends on.
 */
@Service
public class AffiliateService {

    private static final Logger log = LoggerFactory.getLogger(AffiliateService.class);
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    private final AffiliateRepository affiliates;
    private final AffiliateLedgerRepository ledger;

    public AffiliateService(AffiliateRepository affiliates, AffiliateLedgerRepository ledger) {
        this.affiliates = affiliates;
        this.ledger = ledger;
    }

    /** Only ACTIVE codes resolve. A pending application must not already be discounting. */
    @Transactional(readOnly = true)
    public Optional<AffiliateEntity> findActive(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return affiliates.findByCodeNormalisedAndStatus(
                AffiliateEntity.normalise(code), AffiliateStatus.ACTIVE);
    }

    /**
     * @return the first-order discount attached to a code, or 0 when the code is unknown
     *         or inactive. Returning zero rather than throwing is deliberate: a typo in a
     *         creator code should not block checkout, it should just not discount.
     */
    @Transactional(readOnly = true)
    public int firstOrderDiscountBps(String code) {
        return findActive(code).map(AffiliateEntity::getFirstOrderDiscountBps).orElse(0);
    }

    @Transactional
    public void accrue(String code, Long orderId, long grossMinor, Currency currency) {
        if (code == null || orderId == null) {
            return;
        }
        if (ledger.existsByOrderId(orderId)) {
            return;
        }
        findActive(code).ifPresent(affiliate -> {
            long commission = BigDecimal.valueOf(grossMinor)
                    .multiply(BigDecimal.valueOf(affiliate.getCommissionBps()), MC)
                    .divide(BigDecimal.valueOf(10_000), MC)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
            ledger.save(new AffiliateLedgerEntity(
                    affiliate.getId(), orderId, grossMinor, commission, currency, "PAYABLE"));
            log.info("Accrued {} minor commission to affiliate {} on order {}",
                    commission, affiliate.getCodeNormalised(), orderId);
        });
    }

    @Transactional
    public AffiliateEntity apply(Long accountId, String code, String displayName, String channels) {
        String normalised = AffiliateEntity.normalise(code);
        if (affiliates.existsByCodeNormalised(normalised)) {
            throw new com.globalfutservice.web.ApiExceptions.ConflictException(
                    "code_taken", "That code is already in use. Try another.");
        }
        return affiliates.save(new AffiliateEntity(accountId, code, displayName, channels));
    }

    @Transactional
    public AffiliateEntity approve(Long affiliateId, Integer commissionBps, Integer firstOrderDiscountBps) {
        AffiliateEntity affiliate = affiliates.findById(affiliateId)
                .orElseThrow(() -> new com.globalfutservice.web.ApiExceptions.NotFoundException(
                        "No such affiliate."));
        if (commissionBps != null) {
            affiliate.setCommissionBps(commissionBps);
        }
        if (firstOrderDiscountBps != null) {
            affiliate.setFirstOrderDiscountBps(firstOrderDiscountBps);
        }
        affiliate.approve(Instant.now());
        return affiliates.save(affiliate);
    }
}
