package com.globalfutservice.fulfilment;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.notify.FulfilmentAlert;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.scheduling.SchedulerLock;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends the queued paid coin orders to FUT Transfer, through Approve's own release.
 *
 * <p>Runs after the payment has committed, on a schedule, one runner at a time -- never inside
 * the request that took the payment, so nothing here can undo PAID. Each queued order is sent
 * only when it is a coin order, FUT Transfer is on and calls are not paused, the sign-in is on
 * file (and, inside the release, meets the partner's rules), no vendor order exists yet, and
 * it is within GFS_FUTTRANSFER_AUTO_DISPATCH_MAX_K. Anything else is left for Approve, with the
 * reason on the order's vendor history and, where someone has to act, an alert.
 *
 * <p>What the release answers decides the rest. Sent: the order moves to IN_PROGRESS, "Sent
 * automatically". A passing cause -- the cooldown could not be read, the account was submitted
 * too recently, another request is sending it, an error of ours -- is tried again a few times
 * with growing gaps. A refusal or an answer we cannot read goes to Needs review with an alert;
 * the order stays paid where it is.
 */
@Component
public class AutoDispatchWorker {

    private static final Logger log = LoggerFactory.getLogger(AutoDispatchWorker.class);

    /** Tries, including the first, before a passing cause becomes a person's to look at. */
    static final int MAX_TRIES = 5;
    private static final Duration FIRST_RETRY = Duration.ofMinutes(1);
    private static final int BATCH = 20;

    /** What became of one queued order. */
    public enum Outcome { SENT, LEFT_FOR_APPROVE, RETRY, NEEDS_REVIEW, NOT_QUEUED }

    private final AutoDispatchQueue queue;
    private final FulfilmentRelease release;
    private final SupplierFulfilmentService supplier;
    private final VendorControl control;
    private final VendorOrderLedger ledger;
    private final VendorOrderActionLog history;
    private final CredentialVaultService vault;
    private final OrderRepository orders;
    private final NotificationService notifications;
    private final SchedulerLock lock;
    private final AppProperties props;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AutoDispatchWorker(AutoDispatchQueue queue, FulfilmentRelease release, SupplierFulfilmentService supplier,
                              VendorControl control, VendorOrderLedger ledger, VendorOrderActionLog history,
                              CredentialVaultService vault, OrderRepository orders, NotificationService notifications,
                              SchedulerLock lock, AppProperties props, ObjectMapper mapper, Clock clock) {
        this.queue = queue;
        this.release = release;
        this.supplier = supplier;
        this.control = control;
        this.ledger = ledger;
        this.history = history;
        this.vault = vault;
        this.orders = orders;
        this.notifications = notifications;
        this.lock = lock;
        this.props = props;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${gfs.fut-transfer.auto-dispatch.every:PT15S}", initialDelayString = "PT30S")
    public void scheduled() {
        try {
            lock.runExclusively("fut-auto-dispatch", this::runDue);
        } catch (RuntimeException e) {
            log.error("Automatic dispatch run failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Every queued order whose turn has come. */
    public void runDue() {
        List<Long> due = queue.due(clock.instant(), BATCH);
        for (Long orderId : due) {
            try {
                process(orderId);
            } catch (RuntimeException e) {
                // One order's trouble never stops the others; it is tried again on the next run.
                log.error("Automatic dispatch of order {} failed: {}", orderId, e.getClass().getSimpleName());
            }
        }
    }

    /** One queued order: sent, left for Approve with the reason, tried again later, or for review. */
    public Outcome process(long orderId) {
        AutoDispatchQueue.Row row = queue.find(orderId).filter(r -> AutoDispatchQueue.QUEUED.equals(r.state()))
                .orElse(null);
        if (row == null) {
            return Outcome.NOT_QUEUED;
        }
        OrderEntity order = orders.findById(orderId).orElse(null);
        if (order == null) {
            queue.settle(orderId, AutoDispatchQueue.LEFT_FOR_APPROVE, "NO_ORDER", "The order no longer exists.");
            return Outcome.LEFT_FOR_APPROVE;
        }
        String ref = order.getPublicRef();

        // ---- what has to be true before anything is read or sent ----------------------------
        if (!props.futTransfer().autoDispatch().enabled()) {
            return leave(order, "SWITCHED_OFF", "Automatic sending was switched off before this order was sent. "
                    + "Approve it to send it.", false);
        }
        if (!order.getSku().isCoinTransfer()) {
            return leave(order, "NOT_A_COIN_ORDER", "Only coin orders are sent to FUT Transfer.", false);
        }
        if (!supplier.isEnabled()) {
            return leave(order, "FUT_TRANSFER_OFF", "FUT Transfer is not switched on, so this paid order was not "
                    + "sent automatically. Work it by hand, or switch FUT Transfer on and approve it.", true);
        }
        if (control.isPaused()) {
            return leave(order, "PAUSED", "Calls to FUT Transfer are paused, so this paid order was not sent "
                    + "automatically. Once calls are resumed, approve it.", true);
        }
        var existing = ledger.find(orderId);
        if (existing.isPresent()) {
            // An admin has sent it, or tried to: the vendor order is theirs to follow.
            queue.settle(orderId, VendorOrderLedger.FAILED.equals(existing.get().state())
                            || VendorOrderLedger.NEEDS_REVIEW.equals(existing.get().state())
                            ? AutoDispatchQueue.LEFT_FOR_APPROVE : AutoDispatchQueue.SENT,
                    "ALREADY_HANDLED", "Already sent, or tried, by an admin (" + existing.get().state() + ").");
            return Outcome.NOT_QUEUED;
        }
        if (order.getStatus() == OrderStatus.CREDENTIALS_PENDING || !vault.status(orderId).present()) {
            // Nobody on our side has anything to do: the customer has been asked for it, and the
            // order is queued again, and sent, the moment it arrives.
            return leave(order, "NO_SIGN_IN", "Paid, waiting for the customer's EA sign-in. It will be sent "
                    + "automatically once they enter it.", false);
        }
        if (order.getStatus() != OrderStatus.READY_FOR_DELIVERY) {
            return leave(order, "NOT_READY", "The order is " + order.getStatus().name() + ", not waiting to be "
                    + "sent, so it was not sent automatically.", false);
        }
        long amountK;
        try {
            amountK = VendorAmount.forOrder(order, mapper);
        } catch (VendorAmount.InvalidAmountException e) {
            return leave(order, "BAD_AMOUNT", e.getMessage() + " It was not sent automatically.", true);
        }
        if (!props.futTransfer().autoDispatch().allows(amountK)) {
            return leave(order, "OVER_LIMIT", amountK + "K is more than the automatic limit of "
                    + props.futTransfer().autoDispatch().maxK() + "K (GFS_FUTTRANSFER_AUTO_DISPATCH_MAX_K), so it "
                    + "waits for an admin. Approve it to send it.", true);
        }

        // ---- the send: Approve's own release, as "Sent automatically" ------------------------
        FulfilmentRelease.Released released;
        try {
            released = release.release(ref, FulfilmentRelease.Releaser.AUTOMATIC);
        } catch (ApiExceptions.ConflictException e) {
            return leave(order, e.code(), e.getMessage() + " It was not sent automatically.", true);
        } catch (RuntimeException e) {
            // Ours, not the partner's: a database blip, say. The release sends at most once
            // whatever happened, so trying again is safe.
            return retry(order, row, "ERROR", "Automatic sending failed (" + e.getClass().getSimpleName() + ").");
        }

        SupplierFulfilmentService.Release r = released.release();
        return switch (r.result()) {
            case SUBMITTED, ALREADY_SUBMITTED -> {
                queue.settle(orderId, AutoDispatchQueue.SENT, null, r.message());
                log.info("Order {} sent automatically to FUT Transfer", ref);
                yield Outcome.SENT;
            }
            // The partner may have it. The release has already put it in review and alerted.
            case NEEDS_REVIEW -> {
                queue.settle(orderId, AutoDispatchQueue.NEEDS_REVIEW, r.result().name(), r.message());
                yield Outcome.NEEDS_REVIEW;
            }
            // Refused sign-in: deleted, the customer asked again, the order on hold, staff alerted.
            case FAILED_SIGN_IN -> {
                queue.settle(orderId, AutoDispatchQueue.LEFT_FOR_APPROVE, r.result().name(), r.message());
                yield Outcome.LEFT_FOR_APPROVE;
            }
            case IN_FLIGHT -> retry(order, row, r.result().name(), r.message());
            case FAILED -> r.retryable() ? retry(order, row, r.result().name(), r.message())
                    : review(order, r.result().name(), r.message());
            case NOT_SENT -> r.retryable() ? retry(order, row, r.result().name(), r.message())
                    : leave(order, r.result().name(), r.message(), true);
        };
    }

    /** Left for Approve: the order stays paid where it is; the reason is on its vendor history. */
    private Outcome leave(OrderEntity order, String code, String reason, boolean tellStaff) {
        queue.settle(order.getId(), AutoDispatchQueue.LEFT_FOR_APPROVE, code, reason);
        history.record(order.getId(), VendorOrderActionLog.Action.AUTO_DISPATCH, null, "automatic",
                VendorOrderActionLog.Outcome.REFUSED, code, reason);
        log.info("Order {} left for Approve: {}", order.getPublicRef(), code);
        if (tellStaff) {
            alert(order, "Not sent automatically", reason, code);
        }
        return Outcome.LEFT_FOR_APPROVE;
    }

    /**
     * Tried and refused, or tried too often: Needs review, with an alert. A refused vendor order
     * moves from FAILED to NEEDS_REVIEW, so the order is on the review list and the admin
     * decides whether to send it again.
     */
    private Outcome review(OrderEntity order, String code, String reason) {
        ledger.move(order.getId(), List.of(VendorOrderLedger.FAILED), VendorOrderLedger.NEEDS_REVIEW, code,
                "Automatic sending: " + reason, null);
        queue.settle(order.getId(), AutoDispatchQueue.NEEDS_REVIEW, code, reason);
        alert(order, "Automatic sending failed: needs review", reason + " The order is still paid.", code);
        return Outcome.NEEDS_REVIEW;
    }

    private Outcome retry(OrderEntity order, AutoDispatchQueue.Row row, String code, String reason) {
        int tries = row.attempts() + 1;
        if (tries >= MAX_TRIES) {
            return review(order, code, reason + " Tried " + tries + " times.");
        }
        // 1, 2, 4, 8 minutes.
        queue.retryAt(order.getId(), clock.instant().plus(FIRST_RETRY.multipliedBy(1L << (tries - 1))), code, reason);
        log.info("Order {} will be sent automatically again later: {}", order.getPublicRef(), code);
        return Outcome.RETRY;
    }

    private void alert(OrderEntity order, String headline, String detail, String code) {
        try {
            notifications.fulfilmentAlert(new FulfilmentAlert(order.getPublicRef(), headline, detail, code,
                    props.publicUrl() + "/admin/orders/" + order.getPublicRef()));
        } catch (RuntimeException e) {
            log.warn("Could not queue the automatic-sending alert for {}: {}", order.getPublicRef(),
                    e.getClass().getSimpleName());
        }
    }
}
