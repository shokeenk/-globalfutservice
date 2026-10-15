package com.globalfutservice.fulfilment;

import java.util.List;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.orders.CustomerAction;
import com.globalfutservice.notify.FulfilmentAlert;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.scheduling.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The customer's corrected sign-in, sent to FUT Transfer by itself.
 *
 * <p>When FUT Transfer refuses a sign-in during the transfer, it holds the order and waits for
 * the customer (AWAITING_CUSTOMER). The customer re-enters their details on their order page;
 * this sends them through the same corrected-sign-in action an admin's button uses
 * ({@code /correctCredentialsAPI}), which restarts the order.
 *
 * <ul>
 *   <li><b>Once per submission</b>, at most once every two minutes per order: each submission is
 *       claimed in the database before anything is sent, whoever gets there first.</li>
 *   <li><b>Refused for the sign-in again</b> (InvalidPassword, InvalidBA1-5): the customer is told
 *       which part is wrong, with the form, by email and on the order's ticket.</li>
 *   <li><b>The partner asked us to wait</b>: sent again once the two minutes have passed.</li>
 *   <li><b>Anything else</b> -- saved but not restarted, refused, no answer we could read -- goes
 *       to an admin, with an alert.</li>
 * </ul>
 * The admin's own button keeps working: whichever sends first, the other finds the partner no
 * longer waiting.
 */
@Component
public class SignInResends {

    private static final Logger log = LoggerFactory.getLogger(SignInResends.class);
    private static final int BATCH = 20;

    static final String NOT_SENT = "SIGN_IN_RESEND_FAILED";

    private final VendorOrderLedger ledger;
    private final VendorOrderActions actions;
    private final VendorControl control;
    private final OrderRepository orders;
    private final OrderService orderService;
    private final CredentialVaultService vault;
    private final NotificationService notifications;
    private final SchedulerLock lock;
    private final AppProperties props;

    public SignInResends(VendorOrderLedger ledger, VendorOrderActions actions, VendorControl control,
                         OrderRepository orders, OrderService orderService, CredentialVaultService vault,
                         NotificationService notifications, SchedulerLock lock, AppProperties props) {
        this.ledger = ledger;
        this.actions = actions;
        this.control = control;
        this.orders = orders;
        this.orderService = orderService;
        this.vault = vault;
        this.notifications = notifications;
        this.lock = lock;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "PT15S", initialDelayString = "PT50S")
    public void scheduled() {
        if (!props.futTransfer().isConfigured()) return;
        try {
            lock.runExclusively("signin-resends", this::sendDue);
        } catch (RuntimeException e) {
            log.error("Corrected sign-ins not sent this round: {}", e.getClass().getSimpleName());
        }
    }

    /** @return how many were sent and restarted the order */
    public int sendDue() {
        // Paused: nothing goes, and every submission stays due for when calls resume.
        if (control.isPaused()) return 0;
        int restarted = 0;
        for (Long orderId : ledger.signInResendsDue(BATCH)) {
            if (!ledger.claimSignInResend(orderId)) continue;
            OrderEntity order = orders.findById(orderId).orElse(null);
            if (order == null) continue;
            try {
                if (settle(order, actions.sendCorrectedSignIn(order, VendorOrderActions.AUTOMATIC))) restarted++;
            } catch (RuntimeException e) {
                toReview(order, "The customer's new sign-in could not be sent (" + e.getClass().getSimpleName()
                        + "). Check the order on the FUT Transfer dashboard, then send it from here.");
            }
        }
        return restarted;
    }

    /** @return whether the order restarted */
    private boolean settle(OrderEntity order, VendorOrderActions.Result r) {
        String ref = order.getPublicRef();
        if (r.status() == VendorOrderActions.Status.DONE && FutTransferClient.CONTINUED.equals(r.code())) {
            log.info("Order {}: the customer's new sign-in was sent and FUT Transfer restarted the order", ref);
            return true;
        }
        if (r.status() == VendorOrderActions.Status.REFUSED && VendorOrderActions.RATE_LIMITED.equals(r.code())) {
            ledger.unclaimSignInResend(order.getId());
            log.info("Order {}: FUT Transfer asked us to wait; the new sign-in goes again in two minutes", ref);
            return false;
        }
        CustomerAction ask = r.status() == VendorOrderActions.Status.REFUSED ? VendorStatusMap.signInAction(r.code())
                : null;
        if (ask != null) {
            askAgain(order, ask, r.code());
            return false;
        }
        if (r.status() == VendorOrderActions.Status.NOT_SENT && !stillWaiting(order)) {
            // An admin sent it first, or the order moved on: nothing is owed.
            log.info("Order {}: the new sign-in was not sent automatically: {}", ref, r.message());
            return false;
        }
        toReview(order, "The customer's new sign-in was not sent automatically: " + r.message());
        return false;
    }

    private boolean stillWaiting(OrderEntity order) {
        return ledger.find(order.getId()).map(row -> "AWAITING_CUSTOMER".equals(row.state())).orElse(false);
    }

    /** Refused for the sign-in again: deleted, and the customer asked for the part that is wrong. */
    private void askAgain(OrderEntity order, CustomerAction ask, String code) {
        String ref = order.getPublicRef();
        ledger.move(order.getId(), List.of("AWAITING_CUSTOMER"), "AWAITING_CUSTOMER", code,
                "FUT Transfer refused the customer's new sign-in (" + code + ").", ask.name());
        ledger.setSignInProblem(order.getId(), ask);
        vault.purge(order.getId(), "corrected sign-in refused by supplier (" + code + ")");
        orderService.askForSignInAgain(orderService.requireAny(ref), ask);
        alert(ref, "Sign-in refused again", "FUT Transfer refused the customer's new sign-in (" + code + "). It has "
                + "been deleted, and the customer has been asked to enter it again.", code);
    }

    private void toReview(OrderEntity order, String reason) {
        String ref = order.getPublicRef();
        if (ledger.move(order.getId(), List.of("AWAITING_CUSTOMER"), VendorOrderLedger.NEEDS_REVIEW, NOT_SENT, reason,
                null)) {
            log.warn("FULFILMENT NEEDS REVIEW: order {} ({})", ref, NOT_SENT);
            alert(ref, "Corrected sign-in not sent", reason, NOT_SENT);
        }
    }

    private void alert(String ref, String headline, String detail, String code) {
        try {
            notifications.fulfilmentAlert(new FulfilmentAlert(ref, headline, detail, code,
                    props.publicUrl() + "/admin/orders/" + ref));
        } catch (RuntimeException e) {
            log.warn("Could not queue the alert for {}: {}", ref, e.getClass().getSimpleName());
        }
    }
}
