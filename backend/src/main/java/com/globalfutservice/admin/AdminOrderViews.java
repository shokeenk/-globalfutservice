package com.globalfutservice.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * What the redesigned Orders page reads.
 *
 * <p>New shapes rather than new fields on {@code OrderDtos.AdminOrderSummary}. That record
 * is also what a customer's own order list is built from, and the queue endpoint that
 * returns it is read by the order page for its transition buttons. Leaving both exactly as
 * they were is what keeps everything that already calls them working.
 */
public final class AdminOrderViews {

    private AdminOrderViews() {
    }

    /** One row of the Orders table. */
    public record Row(
            String publicRef,
            String status,
            String sku,
            String serviceLabel,
            String variant,
            BigDecimal quantity,
            /** The order's platform, or for coaching the platform the player plays on. */
            String platform,
            String deliveryMethod,
            /** A sign-in is in the vault now. */
            boolean credentialsHeld,
            /** The fulfilment partner has this order. */
            boolean withPartner,
            /** The name typed at checkout, else the account's display name, else null. */
            String customerName,
            String customerEmail,
            /** The latest payment claim's status (SUBMITTED, VERIFIED, REJECTED), or null. */
            String paymentState,
            /** How the latest claim says it was paid (UPI, PAYPAL, CRYPTO), or null. */
            String paymentMethod,
            /** The latest claim's UTR, PayPal id or transaction hash, or null. */
            String paymentReference,
            /** The in-game ID given at checkout. An ID, never a password. */
            String eaHandle,
            long totalMinor,
            String totalFormatted,
            String currency,
            Instant createdAt,
            Instant deliveredAt,
            List<String> availableTransitions) {
    }

    public record Page(List<Row> items, long total, int page, int size) {
    }

    public record StatusCount(String sku, String status, long count) {
    }

    /**
     * Everything the cards and tab counts need, in one call.
     *
     * <p>Counts are over every order, not the filtered view: a tab that says "Coaching 18"
     * should say it whatever the search box holds.
     */
    public record Overview(
            List<StatusCount> counts,
            /** Waiting for payment with a claim nobody has checked. */
            long paymentsToCheck,
            /** Ready to work with a sign-in on file. */
            long signInsToWork,
            long disputed,
            /** Waiting for the customer's EA sign-in. */
            long awaitingSignIn,
            /** Orders marked delivered since midnight, in the business's time zone. */
            long deliveredToday,
            /**
             * Delivered yesterday up to this same time of day. Compared against today so far,
             * so the trend is not "down" every morning just because the day has barely
             * started.
             */
            long deliveredYesterdaySoFar,
            /** Sign-ins in the vault right now, across every order. */
            long credentialsHeld) {
    }

    public record ReminderSent(Instant sentAt) {
    }
}
