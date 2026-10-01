package com.globalfutservice.fulfilment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.credentials.web.CredentialDtos;
import com.globalfutservice.notify.FulfilmentAlert;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.orders.OrderEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends a paid coin order to FUT Transfer, exactly once.
 *
 * <p><b>Two ways to send.</b> By configuration, an order is bought from FUT Transfer's public
 * seller pool ({@code /buyCoinsAPI}) or sent from our own sender accounts
 * ({@code /orderAPI}). Everything below applies to both; the vendor order records which
 * was used, and once sent, an order is followed the same way whichever is configured.
 *
 * <p><b>Disabled is a supported state, not a broken one.</b> With no supplier configured
 * the order stops at {@code READY_FOR_DELIVERY} for an operator to work by hand, which is
 * how this system behaved before the integration existed.
 *
 * <p><b>Exactly once.</b> {@link VendorOrderLedger#claim} commits a SUBMITTING row before
 * the vendor is called, and only the caller that wrote it may call. When the answer is
 * lost -- a timeout, a dropped connection, a 5xx, an unreadable reply -- the order may
 * exist at the vendor, so it is looked up by our reference and never placed again. If the
 * lookup cannot prove the order exists, an admin decides. The only outcome that allows a
 * second placement is a definite, documented refusal, and even then only when an admin
 * approves again.
 *
 * <p><b>Credentials are read, used, and dropped.</b> The plaintext exists as a local for
 * the length of one HTTP call. It is never held on a field, never put on the order, and
 * never logged. When the vendor refuses the sign-in itself it is deleted, because it is
 * no use to anyone and the customer is asked for a new one.
 */
@Service
public class SupplierFulfilmentService {

    private static final Logger log = LoggerFactory.getLogger(SupplierFulfilmentService.class);

    private final FutTransferClient client;
    private final VendorControl control;
    private final CredentialVaultService vault;
    private final VendorOrderLedger ledger;
    private final NotificationService notifications;
    private final AppProperties props;
    private final ObjectMapper mapper;

    public SupplierFulfilmentService(FutTransferClient client, VendorControl control, CredentialVaultService vault,
                                     VendorOrderLedger ledger, NotificationService notifications,
                                     AppProperties props, ObjectMapper mapper) {
        this.client = client;
        this.control = control;
        this.vault = vault;
        this.ledger = ledger;
        this.notifications = notifications;
        this.props = props;
        this.mapper = mapper;
    }

    public boolean isEnabled() {
        return client.isEnabled();
    }

    /** What releasing an order came to. */
    public enum Result {
        /** The vendor has the order: it told us its id, or a lookup confirmed it. */
        SUBMITTED,
        /** It already had it before this click. Nothing was sent. */
        ALREADY_SUBMITTED,
        /** Another request is sending it right now. Nothing was sent. */
        IN_FLIGHT,
        /** The vendor refused the customer's sign-in. It has been deleted. */
        FAILED_SIGN_IN,
        /** The vendor refused the order and created nothing. */
        FAILED,
        /** We cannot prove whether the vendor created it. An admin decides. */
        NEEDS_REVIEW,
        /** Not sent, for a reason on our side: see the message. */
        NOT_SENT
    }

    /**
     * @param vendorOrderId the vendor's id, when known; a lookup confirms an order without one
     * @param message       for the admin who clicked; never contains a credential
     */
    public record Release(Result result, String vendorOrderId, String message) {
    }

    /**
     * An operator has reviewed the order and released it to the supplier.
     *
     * <p>The order itself is not moved here. What happened is returned, and the caller
     * moves the order and writes the timeline entry, attributed to whoever clicked.
     * Nothing is thrown for a vendor outcome, so the vault's record of the read commits
     * whatever the vendor said.
     */
    @Transactional
    public Release approveAndDispatch(OrderEntity order, Long operatorAccountId) {
        String ref = order.getPublicRef();
        if (!isEnabled()) {
            return new Release(Result.NOT_SENT, null, "The fulfilment partner is not configured, so "
                    + "this order cannot be released automatically. Work it by hand and mark it in progress.");
        }
        if (!order.getSku().isCoinTransfer()) {
            return new Release(Result.NOT_SENT, null, "The fulfilment partner only takes coin orders.");
        }
        if (control.isPaused()) {
            return new Release(Result.NOT_SENT, null, "Every call to the fulfilment partner is paused, because it "
                    + "refused our API credentials. Fix GFS_FUTTRANSFER_API_USER and GFS_FUTTRANSFER_API_KEY, then "
                    + "resume calls. Nothing was sent.");
        }

        long amountK;
        try {
            amountK = VendorAmount.forOrder(order, mapper);
        } catch (VendorAmount.InvalidAmountException e) {
            return new Release(Result.NOT_SENT, null, e.getMessage() + " Nothing was sent.");
        }

        /*
         * Can it be sent at all? Asked before the vault is opened, so an order that is in
         * flight, with the vendor, or waiting for review never has its sign-in read for
         * nothing. This is not the guard -- the claim below is -- only a courtesy to the vault.
         */
        int maxAttempts = props.futTransfer().maxDispatchAttempts();
        var existing = ledger.find(order.getId());
        if (existing.isPresent() && (!VendorOrderLedger.FAILED.equals(existing.get().state())
                || existing.get().attempts() >= maxAttempts)) {
            return notClaimed(ref, existing.get());
        }

        // How it would be sent, from configuration now. A setting that is needed and
        // missing stops it here, before the vault is opened or anything is claimed.
        PlacementTerms.Decision decision = PlacementTerms.decide(props.futTransfer(), amountK);
        if (decision instanceof PlacementTerms.Refuse refuse) {
            return new Release(Result.NOT_SENT, null, refuse.reason());
        }
        VendorOrderLedger.SendTerms terms = ((PlacementTerms.Send) decision).terms();

        CredentialDtos.RevealedCredentials creds;
        try {
            // As the system, not the operator: the vault attributes every read.
            creds = vault.reveal(order.getId(), null);
        } catch (RuntimeException e) {
            return new Release(Result.NOT_SENT, null,
                    "This order has no sign-in on file, so there is nothing to send. Nothing was sent.");
        }

        if (props.futTransfer().cooldownCheck()) {
            Release cooling = cooldownRefusal(ref, creds);
            if (cooling != null) {
                return cooling;
            }
        }

        VendorOrderLedger.Claim claim = ledger.claim(order.getId(), ref, amountK, maxAttempts, terms);
        if (claim instanceof VendorOrderLedger.NotClaimed nc) {
            return notClaimed(ref, nc.current());
        }

        /*
         * The audit line, written after the claim and before the call. If the process dies
         * mid-request there has still been an outbound submission of this customer's
         * sign-in, and the record of who authorised it must not depend on the request
         * coming back. Order reference and operator only -- never a credential.
         */
        log.info("FULFILMENT APPROVAL: operator {} is releasing order {} ({}K, {}) to the supplier",
                operatorAccountId, ref, amountK, terms.orderMode());

        FutTransferClient.Placement placement;
        if (terms.publicPool()) {
            recordBalance(order.getId(), ref);
            placement = client.buyCoins(ref, customerNameFor(order), order.getPlatform(), amountK, creds,
                    terms.buyNowThreshold(), terms.maxPrice());
        } else {
            placement = client.submitOrder(ref, customerNameFor(order), order.getPlatform(), amountK, creds);
        }

        return switch (placement) {
            case FutTransferClient.Accepted a -> accepted(order, a.vendorOrderId());
            case FutTransferClient.Uncertain u -> resolveUncertain(order, amountK, u.code());
            case FutTransferClient.Refused r -> refused(order, r);
            case FutTransferClient.Unrecognised u -> review(order, u.code(),
                    "The partner answered HTTP " + u.httpStatus() + " (" + u.code() + "), which its "
                            + "documentation does not explain, so we cannot tell whether the order was "
                            + "created. Check the FUT Transfer dashboard for " + ref + ".");
        };
    }

    /**
     * The balance FUT Transfer reports, kept on the vendor order for margin tracking. Read
     * just before the purchase; a failed read is noted in the call log and nothing else --
     * it never stops or delays the send beyond the read itself.
     */
    private void recordBalance(long orderId, String ref) {
        if (client.balance(ref) instanceof FutTransferClient.ReadOk<java.math.BigDecimal> ok) {
            ledger.recordBalanceAtSend(orderId, ok.value());
        }
    }

    /**
     * The vendor's per-account cooldown, checked before anything is claimed or sent.
     *
     * @return a refusal if the order must not be sent now, or null to go ahead
     */
    private Release cooldownRefusal(String ref, CredentialDtos.RevealedCredentials creds) {
        FutTransferClient.Read<FutTransferClient.Cooldown> read = client.cooldown(creds.eaEmail(), ref);
        if (read instanceof FutTransferClient.ReadOk<FutTransferClient.Cooldown> ok) {
            if (ok.value().ready()) {
                return null;
            }
            long s = ok.value().remainingSeconds();
            return new Release(Result.NOT_SENT, null, "The customer's EA account is in the partner's transfer "
                    + "cooldown for another " + (s / 3600) + "h " + ((s % 3600) / 60) + "m. Nothing was sent; "
                    + "approve it again after then.");
        }
        FutTransferClient.ReadFailed<FutTransferClient.Cooldown> failed =
                (FutTransferClient.ReadFailed<FutTransferClient.Cooldown>) read;
        return new Release(Result.NOT_SENT, null, "Could not check whether the customer's EA account is in the "
                + "partner's transfer cooldown (" + failed.code() + "). Nothing was sent; try again shortly.");
    }

    private Release accepted(OrderEntity order, String vendorOrderId) {
        if (ledger.markSubmitted(order.getId(), vendorOrderId)) {
            log.info("FULFILMENT APPROVED: order {} is supplier order {}", order.getPublicRef(), vendorOrderId);
            return new Release(Result.SUBMITTED, vendorOrderId,
                    "Sent to the fulfilment partner as " + vendorOrderId + ".");
        }
        return review(order, "VENDOR_ID_CONFLICT", "The partner accepted the order as " + vendorOrderId
                + ", but that id is already recorded against another order.");
    }

    /**
     * The answer was lost. The order may exist, so it is looked up by our reference, and
     * never placed again from here.
     */
    private Release resolveUncertain(OrderEntity order, long amountK, String firstError) {
        String ref = order.getPublicRef();
        FutTransferClient.Lookup lookup = client.lookupByReference(ref, amountK);
        if (lookup instanceof FutTransferClient.Found found
                && ledger.markConfirmedByLookup(order.getId(), firstError, found)) {
            log.info("FULFILMENT APPROVED: order {} confirmed at the supplier by lookup after {}", ref, firstError);
            return new Release(Result.SUBMITTED, null, "The partner's answer was lost (" + firstError
                    + "), and a lookup confirmed it has the order.");
        }
        String lookupCode = lookup instanceof FutTransferClient.NotConfirmed nc ? nc.code() : "NOT_RECORDED";
        return review(order, firstError, "The order was sent, but the answer was lost (" + firstError
                + ") and a lookup by " + ref + " could not confirm it exists (" + lookupCode + "). It may have "
                + "been created. Check the FUT Transfer dashboard for " + ref + " before doing anything else. "
                + "Nothing will be sent again automatically.");
    }

    private Release refused(OrderEntity order, FutTransferClient.Refused r) {
        String ref = order.getPublicRef();
        switch (r.reason()) {
            case SIGN_IN_REJECTED -> {
                ledger.markFailed(order.getId(), r.code(), "The partner refused the customer's sign-in.");
                // Useless to anyone now, and the customer is asked for a new one.
                vault.purge(order.getId(), "sign-in refused by supplier (" + r.code() + ")");
                alert(ref, "Sign-in refused", "The partner refused the customer's sign-in (" + r.code()
                        + "). It has been deleted and the customer has been asked to enter it again.", r.code());
                return new Release(Result.FAILED_SIGN_IN, null, "The partner refused the customer's sign-in ("
                        + r.code() + "). Nothing was created. The order is on hold until they enter it again.");
            }
            case AUTH_FAILED -> {
                ledger.markFailed(order.getId(), r.code(), "Our API credentials were refused.");
                alert(ref, "API credentials refused", "The partner refused our API credentials (HTTP 403). "
                        + "Check GFS_FUTTRANSFER_API_USER and GFS_FUTTRANSFER_API_KEY. Nothing was created.", r.code());
                return new Release(Result.FAILED, null, "The partner refused our API credentials (HTTP 403). "
                        + "Nothing was created. Check the API user and key before trying again.");
            }
            case RATE_LIMITED -> {
                ledger.markFailed(order.getId(), r.code(), "The same EA account was submitted too recently.");
                return new Release(Result.FAILED, null, "The partner says this EA account was submitted too "
                        + "recently (HTTP 429). Nothing was created. Try again later.");
            }
            /*
             * The public pool's own refusals. Nothing was created and the sign-in is kept:
             * the cause is on our side or the market's, not the customer's, and once it is
             * fixed an admin approves the order again. Never retried automatically.
             */
            case INSUFFICIENT_FUNDS -> {
                ledger.markFailed(order.getId(), r.code(), "Our FUT Transfer balance does not cover this order.");
                alert(ref, "FUT Transfer balance too low", "FUT Transfer refused " + ref + " because our balance "
                        + "does not cover it (HTTP 402) and created nothing. The customer's sign-in is kept. Top up "
                        + "the FUT Transfer balance, then approve the order again.", r.code());
                return new Release(Result.FAILED, null, "FUT Transfer refused the order: our balance does not "
                        + "cover it (HTTP 402). Nothing was created and the customer's sign-in is kept. Top up the "
                        + "balance, then approve it again.");
            }
            case NO_STOCK -> {
                ledger.markFailed(order.getId(), r.code(), "FUT Transfer's sellers do not have enough coins for it.");
                alert(ref, "Not enough coins at FUT Transfer", "FUT Transfer refused " + ref + " because its "
                        + "sellers do not have enough coins for it right now (HTTP 406) and created nothing. The "
                        + "customer's sign-in is kept. Approve it again later.", r.code());
                return new Release(Result.FAILED, null, "FUT Transfer's sellers do not have enough coins for this "
                        + "order right now (HTTP 406). Nothing was created and the customer's sign-in is kept. "
                        + "Approve it again later.");
            }
            case SUPPLIER_REFUSED -> {
                ledger.markFailed(order.getId(), r.code(), "FUT Transfer refused the order (" + r.code() + ").");
                alert(ref, "Order refused", "FUT Transfer refused " + ref + " (" + r.code() + ") and created "
                        + "nothing. The customer's sign-in is kept. Check the public-pool settings, then approve "
                        + "it again.", r.code());
                return new Release(Result.FAILED, null, "FUT Transfer refused the order (" + r.code() + "). "
                        + "Nothing was created and the customer's sign-in is kept. Check the public-pool settings, "
                        + "then approve it again.");
            }
            default -> {
                ledger.markFailed(order.getId(), r.code(), "The partner refused the order.");
                alert(ref, "Order refused", "The partner refused the order (" + r.code() + ") and created "
                        + "nothing. The customer's sign-in is kept.", r.code());
                return new Release(Result.FAILED, null, "The partner refused the order (" + r.code()
                        + "). Nothing was created.");
            }
        }
    }

    private Release review(OrderEntity order, String code, String reason) {
        ledger.markNeedsReview(order.getId(), code, reason);
        log.error("FULFILMENT NEEDS REVIEW: order {} ({})", order.getPublicRef(), code);
        alert(order.getPublicRef(), "Needs review", reason, code);
        return new Release(Result.NEEDS_REVIEW, null, reason);
    }

    /** The row belongs to someone else, or to a state that cannot be sent from. */
    private Release notClaimed(String ref, VendorOrderLedger.Row row) {
        return switch (row.state()) {
            case VendorOrderLedger.SUBMITTING -> new Release(Result.IN_FLIGHT, null, "Order " + ref
                    + " is already being released. Give it a moment and refresh -- it has not been sent twice.");
            case VendorOrderLedger.NEEDS_REVIEW -> new Release(Result.NOT_SENT, null, "Order " + ref
                    + " needs review before anything else is sent: " + row.reviewReason());
            case VendorOrderLedger.FAILED -> new Release(Result.NOT_SENT, null, "Order " + ref
                    + " has already been tried " + row.attempts() + " times and will not be sent again "
                    + "automatically. Work it by hand and mark it in progress.");
            // The partner has it and is waiting for the customer. Approving again would move
            // the order on without sending anything, and the partner would go on waiting.
            case "AWAITING_CUSTOMER" -> new Release(Result.NOT_SENT, null, "The partner already has order " + ref
                    + " and is waiting for the customer. Once they have entered new details, use Send corrected "
                    + "sign-in. Nothing was sent.");
            case "DELIVERED", "PARTIALLY_DELIVERED", "RESOLVED" -> new Release(Result.NOT_SENT, null, "Order " + ref
                    + " is finished at the partner (" + row.state() + "). Nothing was sent.");
            default -> new Release(Result.ALREADY_SUBMITTED, row.vendorOrderId(), "Order " + ref
                    + " is already with the fulfilment partner. Nothing was sent.");
        };
    }

    private void alert(String ref, String headline, String detail, String code) {
        try {
            notifications.fulfilmentAlert(new FulfilmentAlert(ref, headline, detail, code,
                    props.publicUrl() + "/admin/orders/" + ref));
        } catch (RuntimeException e) {
            // An alert that cannot be sent never changes what happened to the order.
            log.warn("Could not queue the fulfilment alert for {}: {}", ref, e.getMessage());
        }
    }

    private static String customerNameFor(OrderEntity order) {
        if (order.getEaPlatformHandle() != null && !order.getEaPlatformHandle().isBlank()) {
            return order.getEaPlatformHandle();
        }
        // Never the email address: it goes into the supplier's customer record, and their
        // record outlives our vault.
        return order.getPublicRef();
    }
}
