package com.globalfutservice.notify;

import java.time.Instant;

/**
 * A customer says they have paid, and somebody has to go and check.
 *
 * <p>This is the alert the manual payment flow exists around. Money moves outside any
 * gateway, so nothing tells us it arrived; until a person opens a bank statement and
 * matches the reference below, the order sits unpaid and the customer waits. Everything
 * here is chosen to answer one question quickly -- <i>is this money in that account?</i>
 * -- without the operator having to open anything first.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>The customer's EA sign-in. Not the email, not the password, not the backup codes.
 * That is not an oversight to be corrected later by adding fields: this record is the
 * boundary, and the reason it is a record rather than a map is so that no future edit can
 * pass a credential through by accident.
 *
 * <p>Those values are sealed with a per-order key before they touch the database and are
 * read back only through an audited, operator-only reveal. A Discord message is the exact
 * opposite of that: plaintext, retained by a third party, replicated to every client in
 * the channel, and searchable forever by anyone who later joins it. Sending a sign-in
 * there would make the whole vault pointless, because the weakest copy of a secret is the
 * one that defines its safety.
 *
 * <p>{@link #credentialsHeld} carries the only fact an operator needs: whether the
 * sign-in has arrived. Not what it is.
 *
 * @param reference   the UTR / transaction id / txid, exactly as the customer typed it
 * @param destination which account they were told to pay -- where to go looking
 */
public record PaymentClaimNotification(
        String publicRef,
        String serviceLabel,
        String amountFormatted,
        String method,
        String destination,
        String reference,
        String customerEmail,
        String customerDiscord,
        /** The name on the order, if one was given. Nullable -- it is an optional field. */
        String customerName,
        boolean credentialsHeld,
        /**
         * Whether a payment screenshot is attached at the moment this fires.
         *
         * <p>Usually false, and that is not a bug. The storefront records the reference
         * first and uploads the image immediately afterwards as a second request, so at
         * claim time there is genuinely nothing attached yet. The ticket says so honestly
         * and the image is posted into the same channel when it lands, rather than the
         * alert claiming an attachment that does not exist.
         */
        boolean hasProof,
        Instant submittedAt,
        String adminDeepLink) {
}
