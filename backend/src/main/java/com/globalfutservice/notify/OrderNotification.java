package com.globalfutservice.notify;

/**
 * A notification about an order.
 *
 * <p>Carries a reference and a status — <b>never</b> the customer's EA sign-in, and never
 * anything from the credential vault. A WhatsApp message lives forever in someone's
 * phone backup and in Meta's infrastructure; it is a pointer to the admin console, not a
 * copy of the record.
 */
public record OrderNotification(
        String publicRef,
        String status,
        String serviceLabel,
        String amountFormatted,
        String customerEmail,
        /**
         * The Discord handle the customer gave at checkout, if they gave one. Nullable.
         *
         * <p>Here because the operator's own alerts now land in Discord, where a handle is
         * something you can act on rather than a string to copy into another app.
         */
        String customerDiscord,
        String deliveryMethod,
        /**
         * The SKU, as {@code Sku.name()} — TRADING_SERVICE, BOOST_CHAMPS, BOOST_RIVALS,
         * COACHING. Nullable on notifications not raised from an order.
         *
         * <p>Here because the confirmation email branches on it: coins send the customer
         * to their order tracking page, everything else sends them to a Discord ticket.
         * Branching on {@code serviceLabel} instead would mean matching display strings
         * that are translated and rewritten for marketing reasons.
         */
        String sku,
        /** The platform the order is for, or null where the SKU has none. */
        String platform,
        String adminDeepLink,
        /** Coaching orders: platform, in-game ID, rank and focus on one line. Null otherwise. */
        String coachingDetails) {
}
