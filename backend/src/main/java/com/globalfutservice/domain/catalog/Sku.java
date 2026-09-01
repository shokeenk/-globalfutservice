package com.globalfutservice.domain.catalog;

/**
 * What we actually sell.
 *
 * <p><b>Read this before renaming anything.</b> Global FUT Services does not sell in-game
 * currency. It sells a <i>trading service</i> performed on the customer's own account:
 * we locate undervalued cards, transact them, and the resulting coins stay with the
 * customer. All in-game assets remain the property of Electronic Arts Inc.
 *
 * <p>That distinction is not marketing gloss — it is what keeps the payment rails open.
 * "Sale of virtual currency" is on the prohibited-business list of most payment
 * processors, Razorpay included. The enum constant, the invoice line, the gateway item
 * description and the Terms of Service must all say the same thing, because a mismatch
 * discovered during a risk review is how these merchant accounts get terminated.
 */
public enum Sku {

    /** Coin trading service, priced per million coins moved. */
    TRADING_SERVICE("Safe Trading Service", PriceUnit.PER_MILLION, true, true),

    /** FUT Champions win-count push. */
    BOOST_CHAMPS("Champs Boosting", PriceUnit.FLAT, true, false),

    /** Division Rivals ranking push. */
    BOOST_RIVALS("Rivals Boosting", PriceUnit.FLAT, true, false),

    /**
     * 1-to-1 coaching, sold as a single session or a six-session pack — the variant on the
     * rate card decides which, and how many session credits the order grants.
     *
     * <p>No market tax: nothing passes through the transfer market, because nothing is
     * traded. No credentials either — see {@link #mayRequireCredentials()}, which is the
     * reason this SKU never touches the vault.
     */
    COACHING("FUT Classes", PriceUnit.FLAT, true, false),

    /** Player card sourcing. Not sold yet. */
    CARDS("Player Cards", PriceUnit.FLAT, false, false);

    private final String displayName;
    private final PriceUnit unit;
    private final boolean sellable;
    private final boolean marketTaxApplies;

    Sku(String displayName, PriceUnit unit, boolean sellable, boolean marketTaxApplies) {
        this.displayName = displayName;
        this.unit = unit;
        this.sellable = sellable;
        this.marketTaxApplies = marketTaxApplies;
    }

    public String displayName() {
        return displayName;
    }

    public PriceUnit unit() {
        return unit;
    }

    /** False for SKUs that exist in the catalogue but are not yet orderable. */
    public boolean sellable() {
        return sellable;
    }

    /**
     * Whether the EA transfer-market tax is passed through as a visible line item.
     * Only applies where delivery goes through the auction house.
     */
    public boolean marketTaxApplies() {
        return marketTaxApplies;
    }

    /**
     * Whether fulfilling this SKU requires the customer's EA sign-in.
     *
     * <p>Only true when the order is fulfilled by comfort trade. Player-auction delivery
     * needs no credentials at all, which deletes an entire class of risk — see
     * {@link com.globalfutservice.domain.orders.DeliveryMethod}.
     *
     * <p><b>COACHING is excluded deliberately, and that is load-bearing.</b> Coaching is
     * teaching: the customer plays their own account while a coach watches and talks. There
     * is never a reason for the vault to hold an EA sign-in for a coaching order, so the
     * whole highest-severity subsystem is bypassed rather than merely unused. If a future
     * change makes a coaching order ask for credentials, that is a bug in the change, not
     * a gap here.
     */
    public boolean mayRequireCredentials() {
        return this == TRADING_SERVICE || this == BOOST_CHAMPS || this == BOOST_RIVALS;
    }

    /** True for SKUs fulfilled by scheduled sessions rather than by an operator queue. */
    public boolean isScheduled() {
        return this == COACHING;
    }
}
