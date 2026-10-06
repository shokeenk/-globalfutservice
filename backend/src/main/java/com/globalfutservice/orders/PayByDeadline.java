package com.globalfutservice.orders;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.payments.ClaimStatus;
import com.globalfutservice.payments.ManualPaymentClaimRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * When an unpaid order stops being payable, and the abandoned-checkout sweep may close it.
 *
 * <p>One rule, read by the sweep and shown to the customer as "Pay by", so the two can
 * never disagree:
 *
 * <ul>
 *   <li>The order's creation plus the delivery window ({@code gfs.fulfilment.delivery-sla},
 *       48 hours). Resuming the payment does not move it.</li>
 *   <li>If staff reject the customer's payment claim after that point, the customer has
 *       {@link #AFTER_REJECTION} from the rejection to pay again: they were waiting on us,
 *       and the deadline passed while they did.</li>
 * </ul>
 *
 * <p>While a claim is waiting to be checked, or a Payop invoice can still be paid, the
 * sweep leaves the order alone whatever this says (see {@link OrderRepository#findStaleUnpaid}).
 */
@Component
public class PayByDeadline {

    /** How long a customer has to pay again after a late rejection. */
    public static final Duration AFTER_REJECTION = Duration.ofHours(24);

    private final ManualPaymentClaimRepository claims;
    private final AppProperties props;

    public PayByDeadline(ManualPaymentClaimRepository claims, AppProperties props) {
        this.claims = claims;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public Instant forOrder(OrderEntity order) {
        Instant base = order.getCreatedAt().plus(props.fulfilment().deliverySla());
        return claims.findFirstByOrderIdAndStatusOrderByReviewedAtDesc(order.getId(), ClaimStatus.REJECTED)
                .map(rejected -> rejected.getReviewedAt())
                .filter(rejectedAt -> rejectedAt != null && rejectedAt.isAfter(base))
                .map(rejectedAt -> rejectedAt.plus(AFTER_REJECTION))
                .orElse(base);
    }
}
