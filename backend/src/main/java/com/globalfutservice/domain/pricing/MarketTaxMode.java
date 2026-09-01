package com.globalfutservice.domain.pricing;

/**
 * Who pays EA's 5% cut on a transfer.
 *
 * <p>EA takes a percentage of every sale on the transfer market. That is not optional and
 * not ours — the only question is whether the customer is charged it on top of the listed
 * price, or whether the listed price already covers it.
 *
 * <p>This exists because the two answers had drifted apart. The storefront advertised that
 * Global FUT Services covers the tax, and the checkout added it as a line on the bill; a
 * customer reading the FAQ and then the total saw two different claims. A mode makes the
 * question explicit and answerable in configuration, rather than implied by whether
 * someone remembered to set {@code marketTaxBps} to zero.
 *
 * <p>Deliberately not modelled on {@link GatewayFeeMode}'s three values. A gateway fee is
 * deducted from what we receive, so "gross up" means solving for a charge that nets to the
 * right amount. EA's tax is taken from the coins, not from the payment, so there is no
 * equivalent third case — either the sticker price includes it or it does not.
 */
public enum MarketTaxMode {

    /**
     * The listed price already covers it. Nothing is added at checkout.
     *
     * <p>The line still appears on the quote at zero, because "we cover the EA tax" is
     * only persuasive if the customer can see the place where it would otherwise have
     * been charged.
     */
    INCLUDED,

    /** Added to the bill as its own line, on top of the listed price. */
    ADDED
}
