package com.globalfutservice.domain.orders;

/**
 * How a trading-service order is actually fulfilled. The two methods have completely
 * different risk, cost and data-handling profiles, so the order row records which one
 * was used rather than leaving it implicit in an operator's head.
 */
public enum DeliveryMethod {

    /**
     * The customer lists a low-value card on the transfer market at an agreed buy-now
     * price and the purchase is made against it.
     *
     * <p><b>This now requires a sign-in.</b> It did not when fulfilment was an operator
     * buying the card by hand — the whole point of the method was that it needed no
     * credentials at all. Routing these orders through the futtransfer supplier changed
     * that, and the flag has to follow: declared {@code false}, the state machine skips
     * {@code CREDENTIALS_PENDING} entirely and the order reaches an operator with no
     * sign-in attached, while the storefront tells the customer their password is never
     * needed. The code and the promise have to say the same thing.
     *
     * <p>EA still takes its 5% market tax, which is why {@code MARKET_TAX} appears as a
     * pass-through line on the quote. This remains the lower-risk method: a card is
     * listed and bought rather than the account being played on.
     */
    PLAYER_AUCTION(true),

    /**
     * Our trader signs in to the customer's account and trades directly.
     *
     * <p>Faster and better for large volumes, but it means this application briefly holds
     * a live EA sign-in. Everything in {@code domain.crypto.EnvelopeCipher} and the
     * credential-vault purge job exists because of this one enum constant.
     */
    COMFORT_TRADE(true),

    /**
     * Fulfilled by booked coaching sessions rather than by an operator queue.
     *
     * <p>Its own constant rather than reusing PLAYER_AUCTION. The two once agreed on
     * "does this need a sign-in" and no longer do — coaching genuinely never needs one,
     * because the player plays their own account while the coach watches. They were
     * always read for different questions anyway: collapsing them would have put coaching
     * orders in the trading queue where no operator can action them.
     */
    SCHEDULED_SESSION(false);

    private final boolean requiresCredentials;

    DeliveryMethod(boolean requiresCredentials) {
        this.requiresCredentials = requiresCredentials;
    }

    public boolean requiresCredentials() {
        return requiresCredentials;
    }
}
