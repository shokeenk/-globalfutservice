package com.globalfutservice.fulfilment;

import java.util.List;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.credentials.web.CredentialDtos;
import com.globalfutservice.domain.orders.Actor;
import com.globalfutservice.domain.orders.CustomerAction;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * What an admin can do to an order FUT Transfer already has.
 *
 * <p>None of these ever creates an order at the vendor: they change, restart, stop or
 * close the one it has, by the vendor's own id. Each is claimed or checked in the
 * database before anything is sent, sent once to the primary domain, and recorded in
 * {@link VendorOrderActionLog} with who did it and what came of it. An answer that was
 * lost is reported as such, never as done.
 *
 * <p>Not transactional: every ledger write commits on its own, before and after the
 * vendor is called, exactly as the release does.
 */
@Service
public class VendorOrderActions {

    private static final Logger log = LoggerFactory.getLogger(VendorOrderActions.class);

    private static final String AWAITING_CUSTOMER = "AWAITING_CUSTOMER";

    private final FutTransferClient client;
    private final VendorControl control;
    private final VendorOrderLedger ledger;
    private final VendorOrderActionLog actions;
    private final CredentialVaultService vault;
    private final OrderService orderService;
    private final AppProperties props;

    public VendorOrderActions(FutTransferClient client, VendorControl control, VendorOrderLedger ledger,
                              VendorOrderActionLog actions, CredentialVaultService vault, OrderService orderService,
                              AppProperties props) {
        this.client = client;
        this.control = control;
        this.ledger = ledger;
        this.actions = actions;
        this.vault = vault;
        this.orderService = orderService;
        this.props = props;
    }

    /** How an action ended, for the admin who clicked. */
    public enum Status {
        /** The vendor confirmed it. */
        DONE,
        /** Not sent, for a reason on our side: the message says what to do first. */
        NOT_SENT,
        /** The vendor answered and did not do it. Nothing changed. */
        REFUSED,
        /** No answer we could read. It may have happened. */
        UNCERTAIN
    }

    /** @param message for the admin; written by us, never containing a sign-in */
    public record Result(Status status, String message) {
    }

    /** Who is acting, for the audit trail and the order's timeline. */
    public record Admin(Long id, String label) {
    }

    /**
     * Sends the customer's corrected sign-in to the order the vendor is holding for them,
     * and restarts it. The customer has re-entered their details, which moved the order
     * from on hold back to ready; this is what gets those details to the vendor.
     */
    public Result sendCorrectedSignIn(OrderEntity order, Admin admin) {
        String ref = order.getPublicRef();
        Result blocked = blocked();
        if (blocked != null) return blocked;

        VendorOrderLedger.Row row = ledger.find(order.getId()).orElse(null);
        if (row == null || VendorOrderLedger.FAILED.equals(row.state())) {
            return notSent("The partner has no order for " + ref + " to correct: nothing was created there. "
                    + "Approve the order to send it.");
        }
        if (!AWAITING_CUSTOMER.equals(row.state())) {
            return notSent("The partner is not waiting for new details on " + ref + " (it is " + row.state()
                    + "). Nothing was sent.");
        }
        if (row.vendorOrderId() == null) {
            return notSent("We have no partner order id for " + ref + ", so the correction cannot be addressed "
                    + "to it. Correct it on the FUT Transfer dashboard.");
        }
        if (order.getStatus() != OrderStatus.READY_FOR_DELIVERY) {
            return notSent("The customer has not entered new details for " + ref + " yet (the order is "
                    + order.getStatus() + "). Nothing was sent.");
        }

        CredentialDtos.RevealedCredentials creds;
        try {
            // As the system, like the release: the vault attributes every read.
            creds = vault.reveal(order.getId(), null);
        } catch (RuntimeException e) {
            return notSent("This order has no sign-in on file, so there is nothing to send. Nothing was sent.");
        }

        if (!ledger.claimRestart(order.getId(), List.of(AWAITING_CUSTOMER), props.futTransfer().polling().submittingGrace())) {
            return notSent("The corrected sign-in for " + ref + " is already being sent. Refresh in a moment -- it "
                    + "has not been sent twice.");
        }
        log.info("FULFILMENT ACTION: operator {} is sending a corrected sign-in for order {} (partner order {})",
                admin.id(), ref, row.vendorOrderId());

        FutTransferClient.Change change = client.correctSignIn(row.vendorOrderId(), ref, creds);
        return switch (change) {
            case FutTransferClient.Changed c when FutTransferClient.CONTINUED.equals(c.outcome()) -> {
                ledger.markRestarted(order.getId(), List.of(AWAITING_CUSTOMER));
                startOrder(order, admin);
                yield done(order, VendorOrderActionLog.Action.SEND_SIGN_IN, admin,
                        "Sent the corrected sign-in; the partner restarted the order.");
            }
            case FutTransferClient.Changed c -> {
                // Saved, not restarted. The claim goes, so Resume is not blocked behind it.
                ledger.releaseRestart(order.getId());
                actions.record(order.getId(), VendorOrderActionLog.Action.SEND_SIGN_IN, admin.id(), admin.label(),
                        VendorOrderActionLog.Outcome.DONE, FutTransferClient.SAVED,
                        "The partner saved the new details but did not restart the order.");
                yield new Result(Status.DONE, "The partner saved the new details for " + ref + " but did not "
                        + "restart the order. Check why on the FUT Transfer dashboard, then resume it.");
            }
            case FutTransferClient.NotChanged n -> {
                ledger.releaseRestart(order.getId());
                yield refused(order, VendorOrderActionLog.Action.SEND_SIGN_IN, admin, n,
                        "The partner would not take the new details");
            }
            case FutTransferClient.ChangeUncertain u -> uncertain(order, VendorOrderActionLog.Action.SEND_SIGN_IN,
                    admin, u.code(), "Could not confirm the partner received the new details for " + ref + " ("
                            + u.code() + "). The next status check will show whether the order restarted; check "
                            + "the FUT Transfer dashboard before sending them again.");
        };
    }

    // ------------------------------------------------------------------ shared ---

    /** Calls are off, or paused after our credentials were refused. */
    private Result blocked() {
        if (!client.isEnabled()) {
            return notSent("The fulfilment partner is not configured. Nothing was sent.");
        }
        if (control.isPaused()) {
            return notSent("Every call to the fulfilment partner is paused, because it refused our API "
                    + "credentials. Fix them and resume calls first. Nothing was sent.");
        }
        return null;
    }

    /** Ready or on hold to in progress, in the customer's words. */
    private void startOrder(OrderEntity order, Admin admin) {
        OrderEntity current = orderService.requireAny(order.getPublicRef());
        if (current.getStatus() == OrderStatus.READY_FOR_DELIVERY || current.getStatus() == OrderStatus.ON_HOLD) {
            orderService.transition(current, OrderStatus.IN_PROGRESS, Actor.OPERATOR, admin.id(), admin.label(),
                    CustomerText.forState(VendorStatusMap.State.SUBMITTED, CustomerAction.NONE));
        }
    }

    private static Result notSent(String message) {
        return new Result(Status.NOT_SENT, message);
    }

    private Result done(OrderEntity order, VendorOrderActionLog.Action action, Admin admin, String detail) {
        actions.record(order.getId(), action, admin.id(), admin.label(), VendorOrderActionLog.Outcome.DONE, null,
                detail);
        return new Result(Status.DONE, detail);
    }

    private Result refused(OrderEntity order, VendorOrderActionLog.Action action, Admin admin,
                           FutTransferClient.NotChanged n, String what) {
        String message = switch (n.refusal()) {
            case AUTH -> what + " (" + n.code() + "): our API credentials were refused, and every call is now "
                    + "paused until they are fixed. Nothing changed.";
            case RATE_LIMITED -> what + " (" + n.code() + ") yet: it asked us to wait. Nothing changed; try "
                    + "again later.";
            case REFUSED -> what + " (" + n.code() + "). Nothing changed.";
        };
        actions.record(order.getId(), action, admin.id(), admin.label(), VendorOrderActionLog.Outcome.REFUSED,
                n.code(), message);
        return new Result(Status.REFUSED, message);
    }

    private Result uncertain(OrderEntity order, VendorOrderActionLog.Action action, Admin admin, String code,
                             String message) {
        log.warn("FULFILMENT ACTION UNCERTAIN: {} on order {} ({})", action, order.getPublicRef(), code);
        actions.record(order.getId(), action, admin.id(), admin.label(), VendorOrderActionLog.Outcome.UNCERTAIN,
                code, message);
        return new Result(Status.UNCERTAIN, message);
    }
}
