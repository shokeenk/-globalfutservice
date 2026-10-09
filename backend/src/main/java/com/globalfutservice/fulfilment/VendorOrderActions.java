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
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public VendorOrderActions(FutTransferClient client, VendorControl control, VendorOrderLedger ledger,
                              VendorOrderActionLog actions, CredentialVaultService vault, OrderService orderService,
                              AppProperties props, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.mapper = mapper;
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

    /**
     * Who is acting.
     *
     * @param label    their email, for the vendor audit trail, which only staff read
     * @param publicId their opaque account id, for the order's timeline -- which the
     *                 customer's own API reads, so it never carries a staff email
     */
    public record Admin(Long id, String label, String publicId) {
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

    /**
     * Restarts an order the vendor interrupted, once whatever stopped it has been fixed: the
     * customer signed out of their console, cleared their unassigned items, or the vendor's
     * own trouble has passed.
     */
    public Result resume(OrderEntity order, Admin admin) {
        String ref = order.getPublicRef();
        Result blocked = blocked();
        if (blocked != null) return blocked;
        VendorOrderLedger.Row row = ledger.find(order.getId()).orElse(null);
        Result unusable = needsVendorOrder(ref, row, RESUMABLE);
        if (unusable != null) return unusable;

        if (!ledger.claimRestart(order.getId(), RESUMABLE, props.futTransfer().polling().submittingGrace())) {
            return notSent("Order " + ref + " is already being restarted. Refresh in a moment.");
        }
        log.info("FULFILMENT ACTION: operator {} is resuming order {} (partner order {})", admin.id(), ref,
                row.vendorOrderId());
        return switch (client.resume(row.vendorOrderId(), ref)) {
            case FutTransferClient.Changed c -> {
                ledger.markRestarted(order.getId(), RESUMABLE);
                startOrder(order, admin);
                yield done(order, VendorOrderActionLog.Action.RESUME, admin, "Resumed at the partner.");
            }
            case FutTransferClient.NotChanged n -> {
                ledger.releaseRestart(order.getId());
                yield refused(order, VendorOrderActionLog.Action.RESUME, admin, n,
                        n.refusal() == FutTransferClient.Refusal.RATE_LIMITED
                                ? "The partner will not resume it during the cooldown after a console sign-in failure"
                                : "The partner would not resume the order");
            }
            case FutTransferClient.ChangeUncertain u -> uncertain(order, VendorOrderActionLog.Action.RESUME, admin,
                    u.code(), "Could not confirm the partner resumed " + ref + " (" + u.code() + "). The next "
                            + "status check will show it; check the FUT Transfer dashboard before trying again.");
        };
    }

    /**
     * Stops the order at the vendor. What it has delivered stays delivered; the poll goes on
     * reading it, so how it ends -- and how much arrived -- is recorded as usual.
     */
    public Result stop(OrderEntity order, Admin admin) {
        String ref = order.getPublicRef();
        Result blocked = blocked();
        if (blocked != null) return blocked;
        VendorOrderLedger.Row row = ledger.find(order.getId()).orElse(null);
        Result unusable = needsVendorOrder(ref, row, STOPPABLE);
        if (unusable != null) return unusable;

        log.info("FULFILMENT ACTION: operator {} is stopping order {} (partner order {})", admin.id(), ref,
                row.vendorOrderId());
        return switch (client.stop(row.vendorOrderId(), ref)) {
            case FutTransferClient.Changed c -> done(order, VendorOrderActionLog.Action.STOP, admin,
                    "Stopped at the partner. The next status check records how much was delivered.");
            case FutTransferClient.NotChanged n -> refused(order, VendorOrderActionLog.Action.STOP, admin, n,
                    "The partner would not stop the order");
            case FutTransferClient.ChangeUncertain u -> uncertain(order, VendorOrderActionLog.Action.STOP, admin,
                    u.code(), "Could not confirm the partner stopped " + ref + " (" + u.code() + "). Check the "
                            + "FUT Transfer dashboard before trying again.");
        };
    }

    /**
     * Closes the order at the vendor. Never done automatically: an admin decides the order
     * is finished, having seen what was ordered against what was delivered.
     */
    public Result markFinished(OrderEntity order, Admin admin) {
        String ref = order.getPublicRef();
        Result blocked = blocked();
        if (blocked != null) return blocked;
        VendorOrderLedger.Row row = ledger.find(order.getId()).orElse(null);
        Result unusable = needsVendorOrder(ref, row, FINISHABLE);
        if (unusable != null) return unusable;
        Long delivered = ledger.detail(order.getId()).map(VendorOrderLedger.Detail::deliveredK).orElse(null);

        log.info("FULFILMENT ACTION: operator {} is marking order {} finished (partner order {})", admin.id(), ref,
                row.vendorOrderId());
        return switch (client.markFinished(row.vendorOrderId(), ref)) {
            case FutTransferClient.Changed c -> done(order, VendorOrderActionLog.Action.MARK_FINISHED, admin,
                    "Marked finished at the partner, with " + (delivered == null ? "an unknown amount" : delivered + "K")
                            + " of " + row.amountOrderedK() + "K delivered as last reported.");
            case FutTransferClient.NotChanged n -> refused(order, VendorOrderActionLog.Action.MARK_FINISHED, admin,
                    n, "The partner would not mark the order finished");
            case FutTransferClient.ChangeUncertain u -> uncertain(order, VendorOrderActionLog.Action.MARK_FINISHED,
                    admin, u.code(), "Could not confirm the partner marked " + ref + " finished (" + u.code()
                            + "). Check the FUT Transfer dashboard.");
        };
    }

    /**
     * Nothing was created at the vendor, so the order may be approved again.
     *
     * <p>Two things have to agree: the admin, who has checked the vendor's dashboard and
     * says so, and our lookup by reference, which must not find it. The lookup alone is
     * never proof -- the vendor does not document what it answers for an order it does not
     * have -- which is why the admin's word is required too.
     */
    public Result allowResend(OrderEntity order, Admin admin, boolean confirmedAbsent) {
        String ref = order.getPublicRef();
        if (!confirmedAbsent) {
            return notSent("Check the FUT Transfer dashboard for " + ref + " first, and confirm there is no order "
                    + "there. Nothing was changed.");
        }
        Result blocked = blocked();
        if (blocked != null) return blocked;
        VendorOrderLedger.Row row = ledger.find(order.getId()).orElse(null);
        if (row == null || !VendorOrderLedger.NEEDS_REVIEW.equals(row.state())) {
            return notSent("Only an order waiting for review can be cleared to send again (" + ref + " is "
                    + (row == null ? "not at the partner" : row.state()) + ").");
        }
        if (row.vendorOrderId() != null) {
            return notSent("The partner gave " + ref + " an id (" + row.vendorOrderId() + "), so it exists there. "
                    + "Link it, stop it or resolve it instead.");
        }

        FutTransferClient.Lookup lookup = client.lookupByReference(ref, row.amountOrderedK());
        if (lookup instanceof FutTransferClient.Found) {
            return refusedByCheck(order, VendorOrderActionLog.Action.RETRY, admin, "FOUND",
                    "The partner does have " + ref + ". Link it instead of sending it again. Nothing was changed.");
        }
        String code = ((FutTransferClient.NotConfirmed) lookup).code();
        if ("AMOUNT_MISMATCH".equals(code)) {
            return refusedByCheck(order, VendorOrderActionLog.Action.RETRY, admin, code, "The partner has an order "
                    + "under " + ref + " for a different amount. Check the dashboard; nothing was changed.");
        }
        if (unanswered(code)) {
            return refusedByCheck(order, VendorOrderActionLog.Action.RETRY, admin, code, "Could not check with the "
                    + "partner (" + code + "). Try again shortly; nothing was changed.");
        }
        if (!ledger.allowResend(order.getId())) {
            return notSent("Order " + ref + " changed while this was checked. Refresh and look again.");
        }
        int max = props.futTransfer().maxDispatchAttempts();
        return done(order, VendorOrderActionLog.Action.RETRY, admin, "Checked: the partner has no order for " + ref
                + " (" + code + "). Cleared to send again -- approve the order to send it."
                + (row.attempts() >= max ? " It has already been tried " + row.attempts() + " times, so it will "
                + "have to be worked by hand." : ""));
    }

    /**
     * The vendor does have the order: it is watched again, as sent. Verified first -- by
     * the vendor's id, if the admin gives one or we hold one, otherwise by our reference --
     * and only for the amount we ordered.
     */
    public Result link(OrderEntity order, Admin admin, String givenVendorOrderId) {
        String ref = order.getPublicRef();
        Result blocked = blocked();
        if (blocked != null) return blocked;
        VendorOrderLedger.Row row = ledger.find(order.getId()).orElse(null);
        if (row == null || !VendorOrderLedger.NEEDS_REVIEW.equals(row.state())) {
            return notSent("Only an order waiting for review can be linked (" + ref + " is "
                    + (row == null ? "not at the partner" : row.state()) + ").");
        }
        String given = givenVendorOrderId == null || givenVendorOrderId.isBlank() ? null : givenVendorOrderId.trim();
        if (given != null && !given.matches("[A-Za-z0-9-]{1,64}")) {
            return notSent("That does not look like a partner order id.");
        }
        if (given != null && row.vendorOrderId() != null && !given.equals(row.vendorOrderId())) {
            return notSent(ref + " is already linked to partner order " + row.vendorOrderId() + ".");
        }
        String id = given != null ? given : row.vendorOrderId();

        String problem;
        if (id != null) {
            problem = verifyById(id, ref, row.amountOrderedK());
        } else {
            FutTransferClient.Lookup lookup = client.lookupByReference(ref, row.amountOrderedK());
            problem = lookup instanceof FutTransferClient.NotConfirmed nc
                    ? "the partner did not confirm an order under " + ref + " for " + row.amountOrderedK() + "K ("
                    + nc.code() + ")" : null;
        }
        if (problem != null) {
            return refusedByCheck(order, VendorOrderActionLog.Action.LINK, admin, "NOT_VERIFIED",
                    "Not linked: " + problem + ". Nothing was changed.");
        }
        if (!ledger.link(order.getId(), given)) {
            return notSent("Not linked: partner order " + given + " is already linked to another of our orders, or "
                    + ref + " changed meanwhile. Nothing was changed.");
        }
        startOrder(order, admin);
        return done(order, VendorOrderActionLog.Action.LINK, admin, "Linked to the partner's order"
                + (id == null ? " under " + ref : " " + id) + " and watched again.");
    }

    /**
     * Closed by hand, with the admin's note: refunded, delivered by other means, given up.
     * Nothing is sent to the vendor, and the order itself is not moved -- refund or deliver
     * it as usual.
     */
    public Result resolve(OrderEntity order, Admin admin, String note) {
        String ref = order.getPublicRef();
        String text = note == null ? "" : note.trim();
        if (text.length() < 5 || text.length() > 500) {
            return notSent("Say how " + ref + " was settled, in 5 to 500 characters.");
        }
        if (!ledger.resolve(order.getId(), text)) {
            String state = ledger.find(order.getId()).map(VendorOrderLedger.Row::state).orElse("not at the partner");
            return notSent("Only an order waiting for a decision can be resolved (" + ref + " is " + state + ").");
        }
        return done(order, VendorOrderActionLog.Action.RESOLVE, admin, "Resolved: " + text);
    }

    // ------------------------------------------------------------- admin view ---

    /**
     * Everything the admin's order page shows about the vendor, and which actions apply now.
     * {@code currentOrderMode} is configuration now; the vendor order says how it was sent.
     * {@code nextSend} is what Approve would send for this order right now -- the same
     * decision Approve takes -- shown before anyone clicks it. Null when FUT Transfer is off or
     * the order is not a coin order.
     */
    public record Section(boolean enabled, boolean paused, VendorOrderLedger.Detail vendorOrder,
                          List<String> available, List<VendorCallLog.Call> calls,
                          List<VendorOrderActionLog.Entry> actions, String currentOrderMode,
                          OrderModeReport.NextSend nextSend, Tracking tracking) {
    }

    /**
     * What the customer can follow: when their transfer started, and the tracking page they
     * were sent, both null until it starts. FUT Transfer's own progress page is not linked:
     * its address carries a code that none of its API's answers include, and the order's id
     * does not lead to it.
     */
    public record Tracking(java.time.Instant transferStartedAt, String customerUrl) {
    }

    public Section section(OrderEntity order, List<VendorCallLog.Call> calls) {
        VendorOrderLedger.Detail d = ledger.detail(order.getId()).orElse(null);
        java.time.Instant started = order.getTransferStartedAt();
        Tracking tracking = new Tracking(started, started == null ? null
                : com.globalfutservice.orders.TrackingLinks.trackUrl(props.publicUrl(), order.getPublicRef()));
        return new Section(client.isEnabled(), control.isPaused(), d, available(d, order.getStatus()), calls,
                actions.forOrder(order.getId()), props.futTransfer().orderMode().name(), nextSend(order), tracking);
    }

    private OrderModeReport.NextSend nextSend(OrderEntity order) {
        if (!client.isEnabled() || !order.getSku().isCoinTransfer()) {
            return null;
        }
        long amountK;
        try {
            amountK = VendorAmount.forOrder(order, mapper);
        } catch (VendorAmount.InvalidAmountException e) {
            OrderModeReport.NextSend shape = OrderModeReport.nextSend(props.futTransfer(), 1);
            return new OrderModeReport.NextSend(shape.orderMode(), shape.endpoint(), shape.transferMethod(), null,
                    null, null, shape.topUpEnabled(), shape.autoFinishCycle(), shape.senderGroup(),
                    e.getMessage() + " Nothing would be sent.");
        }
        return OrderModeReport.nextSend(props.futTransfer(), amountK);
    }

    /** The same rules each action checks, so the page offers only what would be tried. */
    static List<String> available(VendorOrderLedger.Detail d, OrderStatus orderStatus) {
        List<String> out = new java.util.ArrayList<>();
        if (d == null) return out;
        boolean hasId = d.vendorOrderId() != null;
        if (hasId && AWAITING_CUSTOMER.equals(d.state()) && orderStatus == OrderStatus.READY_FOR_DELIVERY) {
            out.add(VendorOrderActionLog.Action.SEND_SIGN_IN.name());
        }
        if (hasId && RESUMABLE.contains(d.state())) out.add(VendorOrderActionLog.Action.RESUME.name());
        if (hasId && STOPPABLE.contains(d.state())) out.add(VendorOrderActionLog.Action.STOP.name());
        if (hasId && FINISHABLE.contains(d.state())) out.add(VendorOrderActionLog.Action.MARK_FINISHED.name());
        if (VendorOrderLedger.NEEDS_REVIEW.equals(d.state())) {
            if (!hasId) out.add(VendorOrderActionLog.Action.RETRY.name());
            out.add(VendorOrderActionLog.Action.LINK.name());
        }
        if (VendorOrderLedger.RESOLVABLE.contains(d.state())) out.add(VendorOrderActionLog.Action.RESOLVE.name());
        return out;
    }

    private static final List<String> RESUMABLE = List.of(AWAITING_CUSTOMER, "NEEDS_REVIEW");
    private static final List<String> STOPPABLE = List.of("SUBMITTED", "IN_DELIVERY", AWAITING_CUSTOMER, "NEEDS_REVIEW");
    private static final List<String> FINISHABLE = List.of("SUBMITTED", "IN_DELIVERY", AWAITING_CUSTOMER, "NEEDS_REVIEW",
            "PARTIALLY_DELIVERED");

    // ------------------------------------------------------------------ checks ---

    /** An action that needs the vendor's own order, in one of {@code states}. */
    private static Result needsVendorOrder(String ref, VendorOrderLedger.Row row, List<String> states) {
        if (row == null) {
            return notSent("The partner has no order for " + ref + ". Nothing was sent.");
        }
        if (!states.contains(row.state())) {
            return notSent("That cannot be done to " + ref + " while it is " + row.state() + ". Nothing was sent.");
        }
        if (row.vendorOrderId() == null) {
            return notSent("We have no partner order id for " + ref + ", so it cannot be addressed. Link it first, "
                    + "or do this on the FUT Transfer dashboard.");
        }
        return null;
    }

    /** The vendor has this id, for our reference and the amount we ordered. Null if so, else what is wrong. */
    private String verifyById(String vendorOrderId, String ref, long amountK) {
        FutTransferClient.Read<java.util.Map<String, FutTransferClient.SupplierStatus>> read =
                client.statusByVendorIds(java.util.Map.of(vendorOrderId, ref));
        if (read instanceof FutTransferClient.ReadFailed<java.util.Map<String, FutTransferClient.SupplierStatus>> f) {
            return "could not check with the partner (" + f.code() + ")";
        }
        FutTransferClient.SupplierStatus s =
                ((FutTransferClient.ReadOk<java.util.Map<String, FutTransferClient.SupplierStatus>>) read).value()
                        .get(vendorOrderId);
        if (s == null) return "the partner does not know order " + vendorOrderId;
        if (s.amountOrderedK() == null || s.amountOrderedK() != amountK) {
            return "partner order " + vendorOrderId + " is for " + s.amountOrderedK() + "K, not " + amountK + "K";
        }
        return null;
    }

    /** A lookup that never got a real answer: it proves nothing either way. */
    private static boolean unanswered(String code) {
        return code == null || code.startsWith("HTTP_5") || java.util.Set.of("TIMEOUT", "CONNECTION_ERROR",
                "INTERRUPTED", "CLIENT_ERROR", "NOT_CONFIGURED", FutTransferClient.PAUSED).contains(code);
    }

    /** Stopped by our own check against the vendor. Recorded, because the vendor was asked. */
    private Result refusedByCheck(OrderEntity order, VendorOrderActionLog.Action action, Admin admin, String code,
                                  String message) {
        actions.record(order.getId(), action, admin.id(), admin.label(), VendorOrderActionLog.Outcome.REFUSED, code,
                message);
        return new Result(Status.NOT_SENT, message);
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
            orderService.transition(current, OrderStatus.IN_PROGRESS, Actor.OPERATOR, admin.id(), admin.publicId(),
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
