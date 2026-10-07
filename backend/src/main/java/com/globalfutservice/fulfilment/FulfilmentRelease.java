package com.globalfutservice.fulfilment;

import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.orders.Actor;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.web.ApiExceptions;
import org.springframework.stereotype.Service;

/**
 * Releasing a paid coin order to FUT Transfer: the one way it happens, whoever starts it.
 *
 * <p>An admin's Approve and the automatic queue both come here. This is where the order is
 * checked, handed to {@link SupplierFulfilmentService#approveAndDispatch} -- the vendor order
 * row committed before the call, sent at most once, a lost answer looked up and never sent
 * again, typed refusals, the order mode from configuration -- and then moved on: to
 * IN_PROGRESS once the partner has it, on hold if the partner refused the customer's sign-in.
 * It used to live in the Approve endpoint; it moved here unchanged so the automatic path
 * could not become a second way of sending.
 *
 * <p>The only difference between the two is who is named: "Sent by" the admin on the order's
 * vendor history, or "Sent automatically".
 */
@Service
public class FulfilmentRelease {

    /** Who released it: an admin, or the automatic queue. */
    public record Releaser(VendorOrderActions.Admin admin) {

        public static final Releaser AUTOMATIC = new Releaser(null);

        public static Releaser admin(VendorOrderActions.Admin admin) {
            return new Releaser(admin);
        }

        public boolean automatic() {
            return admin == null;
        }

        Long accountId() {
            return admin == null ? null : admin.id();
        }
    }

    /** What the release came to, and the order as it is now. */
    public record Released(SupplierFulfilmentService.Release release, OrderEntity order) {

        public boolean sent() {
            return release.result() == SupplierFulfilmentService.Result.SUBMITTED
                    || release.result() == SupplierFulfilmentService.Result.ALREADY_SUBMITTED;
        }
    }

    private final OrderService orderService;
    private final CredentialVaultService vault;
    private final SupplierFulfilmentService supplier;
    private final VendorOrderActionLog history;

    public FulfilmentRelease(OrderService orderService, CredentialVaultService vault,
                             SupplierFulfilmentService supplier, VendorOrderActionLog history) {
        this.orderService = orderService;
        this.vault = vault;
        this.supplier = supplier;
        this.history = history;
    }

    /**
     * Releases {@code publicRef}. Throws a {@link ApiExceptions.ConflictException} when the
     * order is not one that can be released -- not a coin order, not paid and holding a
     * sign-in -- before anything is read or sent. Otherwise returns what happened; nothing
     * is thrown for the partner's answer.
     */
    public Released release(String publicRef, Releaser who) {
        OrderEntity order = orderService.requireAny(publicRef);

        if (!order.getSku().isCoinTransfer()) {
            /*
             * The partner takes coin orders. Boosting holds a sign-in exactly like a coin
             * order does, so it arrived here looking releasable and failed inside the
             * client instead -- burning a dispatch attempt to say so.
             */
            throw new ApiExceptions.ConflictException("not_a_coin_order",
                    "The fulfilment partner only takes coin orders. This one is "
                            + order.getSku().displayName() + ", which is worked by hand.");
        }

        /*
         * READY_FOR_DELIVERY is the approval state, and it already existed.
         *
         * It means exactly what an "awaiting admin approval" status would: paid, sign-in
         * held, nothing started. Adding a second status with that meaning would have made
         * every order already sitting in this state ambiguous and bought no behaviour, so
         * the existing one is used and the state machine's READY_FOR_DELIVERY ->
         * IN_PROGRESS edge carries the approval.
         */
        if (order.getStatus() != OrderStatus.READY_FOR_DELIVERY) {
            throw new ApiExceptions.ConflictException("not_awaiting_approval",
                    "Only an order that is paid and holding a sign-in can be released. "
                            + "This one is " + order.getStatus().name() + ".");
        }
        if (!vault.status(order.getId()).present()) {
            // The partner requires the sign-in; releasing without one would be a
            // guaranteed 400 from them and a wasted dispatch attempt against the order.
            throw new ApiExceptions.ConflictException("no_credentials",
                    "This order has no sign-in on file, so there is nothing to send.");
        }

        SupplierFulfilmentService.Release release = supplier.approveAndDispatch(order, who.accountId());

        /*
         * Re-read before moving it, because the release may just have written to this row.
         *
         * The release records the partner's order id and commits, which leaves the copy
         * loaded above one version behind. Transitioning that stale copy failed the
         * optimistic lock *after* the sign-in had already gone to the partner: the
         * operator saw a 500, the order sat in the queue, and only a second click moved
         * it. Loading it again costs one query and makes the successful path succeed.
         */
        OrderEntity released = orderService.requireAny(publicRef);

        switch (release.result()) {
            case SUBMITTED, ALREADY_SUBMITTED -> {
                if (released.getStatus() == OrderStatus.READY_FOR_DELIVERY) {
                    String sent = (who.automatic() ? "Sent automatically to fulfilment partner"
                            : "Released to fulfilment partner")
                            + (release.vendorOrderId() == null ? "" : " as " + release.vendorOrderId());
                    // Labelled with the public id, as every other transition is. Never the email:
                    // the customer's own API returns this timeline.
                    released = who.automatic()
                            ? orderService.transition(released, OrderStatus.IN_PROGRESS, Actor.SYSTEM, null,
                                    "auto-dispatch", sent)
                            : orderService.transition(released, OrderStatus.IN_PROGRESS, Actor.OPERATOR,
                                    who.admin().id(), who.admin().publicId(), sent);
                }
            }
            case FAILED_SIGN_IN ->
                // The sign-in was refused and deleted: the customer is asked for it again.
                // This reason is on the order timeline, which the customer reads.
                released = orderService.transition(released, OrderStatus.ON_HOLD, Actor.SYSTEM, null, "GFS",
                        "Your EA sign-in was not accepted. Please enter your details again so we can start.");
            default -> {
                // The order stays where it was. The reason is in the release's message.
            }
        }

        record(released.getId(), who, release);
        return new Released(release, released);
    }

    /**
     * The order's vendor history: "Sent automatically" or "Sent by" the admin, or why it was
     * not sent. Staff only. Something already with the partner before this release is not
     * credited to it.
     */
    private void record(long orderId, Releaser who, SupplierFulfilmentService.Release release) {
        VendorOrderActionLog.Outcome outcome = switch (release.result()) {
            case SUBMITTED -> VendorOrderActionLog.Outcome.DONE;
            case NEEDS_REVIEW -> VendorOrderActionLog.Outcome.UNCERTAIN;
            default -> VendorOrderActionLog.Outcome.REFUSED;
        };
        String code = release.result() == SupplierFulfilmentService.Result.SUBMITTED ? null
                : release.result() == SupplierFulfilmentService.Result.ALREADY_SUBMITTED ? "ALREADY_SENT"
                : release.result().name();
        history.record(orderId, who.automatic() ? VendorOrderActionLog.Action.AUTO_DISPATCH
                        : VendorOrderActionLog.Action.APPROVE, who.accountId(),
                who.automatic() ? "automatic" : who.admin().label(), outcome, code, release.message());
    }
}
