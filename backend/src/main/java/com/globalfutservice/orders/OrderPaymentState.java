package com.globalfutservice.orders;

import com.globalfutservice.domain.payments.ClaimStatus;
import com.globalfutservice.payments.ManualPaymentClaimRepository;
import com.globalfutservice.payments.payop.PayopInvoiceEntity;
import com.globalfutservice.payments.payop.PayopInvoiceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Where an order stands on payment, in the words the order page needs.
 *
 * <ul>
 *   <li>{@link #UNPAID}: waiting for payment, and payable until {@code payBy}.</li>
 *   <li>{@link #SUBMITTED}: the customer has sent their payment details and nobody has
 *       checked them yet. Still {@code AWAITING_PAYMENT} as an order -- a claim is not a
 *       payment until an operator finds the money.</li>
 *   <li>{@link #EXPIRED}: abandoned, or past its pay-by time with nothing Payop can still
 *       take, so the next sweep will abandon it.</li>
 *   <li>null: payment is behind it.</li>
 * </ul>
 */
@Component
public class OrderPaymentState {

    public static final String UNPAID = "UNPAID";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String EXPIRED = "EXPIRED";

    /** The state, and for an unpaid order the time it can be paid until. */
    public record View(String state, Instant payBy) {
        public static final View NONE = new View(null, null);
    }

    private final ManualPaymentClaimRepository claims;
    private final PayopInvoiceRepository invoices;
    private final PayByDeadline payBy;
    private final Clock clock;

    public OrderPaymentState(ManualPaymentClaimRepository claims, PayopInvoiceRepository invoices,
                             PayByDeadline payBy, Clock clock) {
        this.claims = claims;
        this.invoices = invoices;
        this.payBy = payBy;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public View of(OrderEntity order) {
        if (order.getId() == null) {
            return View.NONE;
        }
        return switch (order.getStatus()) {
            case ABANDONED -> new View(EXPIRED, null);
            case AWAITING_PAYMENT -> awaiting(order);
            default -> View.NONE;
        };
    }

    private View awaiting(OrderEntity order) {
        if (claims.findByOrderIdAndStatus(order.getId(), ClaimStatus.SUBMITTED).isPresent()) {
            return new View(SUBMITTED, null);
        }
        Instant deadline = payBy.forOrder(order);
        Instant now = clock.instant();
        if (!now.isBefore(deadline)
                && invoices.payableUntil(order.getId(), now, PayopInvoiceEntity.Status.CREATING).isEmpty()) {
            return new View(EXPIRED, deadline);
        }
        return new View(UNPAID, deadline);
    }
}
