package com.globalfutservice.domain.orders;

/**
 * The lifecycle of an order. Each constant is a state a customer can be told about and an
 * operator can act on — there are no internal-only statuses hiding here.
 */
public enum OrderStatus {

    /** Quote accepted, nothing charged yet. */
    DRAFT(false),

    /** Razorpay order created; waiting on the customer to pay. */
    AWAITING_PAYMENT(false),

    /** Customer walked away or the payment failed. Terminal. */
    ABANDONED(true),

    /** Payment captured and verified <b>from the webhook</b>, never from the redirect. */
    PAID(false),

    /** Comfort-trade order waiting on the customer to submit their EA sign-in. */
    CREDENTIALS_PENDING(false),

    /** Everything needed is on file; sitting in the operator queue. */
    READY_FOR_DELIVERY(false),

    /** A trader has picked it up. */
    IN_PROGRESS(false),

    /** Blocked on the customer — account online, transfer market locked, unreachable. */
    ON_HOLD(false),

    /**
     * Fulfilment finished.
     *
     * <p>This is a <b>contractual</b> transition, not a cosmetic one. Entering it sends
     * the "Order Delivered" email, closes the refund window, and starts the seven-day
     * guarantee clock. It is operator-only and it is irreversible.
     */
    DELIVERED(false),

    /** Guarantee window elapsed without a claim. Points and commission settle here. */
    COMPLETED(true),

    /** A guarantee claim is open — ban or coin removal reported within seven days. */
    DISPUTED(false),

    /** Money returned. Terminal. */
    REFUNDED(true),

    /** Guarantee honoured as store credit rather than cash. Terminal. */
    CREDITED(true);

    private final boolean terminal;

    OrderStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    /** After this point the customer has been told the job is done. */
    public boolean isPostDelivery() {
        return this == DELIVERED || this == COMPLETED || this == DISPUTED
                || this == REFUNDED || this == CREDITED;
    }

    /** States in which an EA sign-in may legitimately sit in the vault. */
    public boolean mayHoldCredentials() {
        return this == CREDENTIALS_PENDING || this == READY_FOR_DELIVERY
                || this == IN_PROGRESS || this == ON_HOLD || this == DELIVERED;
    }
}
