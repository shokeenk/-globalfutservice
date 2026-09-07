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
        String adminDeepLink) {
}
