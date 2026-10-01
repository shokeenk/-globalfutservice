package com.globalfutservice.fulfilment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.orders.Actor;
import com.globalfutservice.domain.orders.CustomerAction;
import com.globalfutservice.domain.orders.OrderStateMachine;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.notify.CustomerActionNotification;
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
 * Keeps our view of every order at FUT Transfer in step with the vendor's.
 *
 * <p><b>What it asks.</b> Orders with a vendor id are read twenty at a time by that id,
 * the form the vendor documents. Orders a lookup confirmed without one are read one at a
 * time by our reference. Sends still SUBMITTING after a grace period -- the process that
 * sent them is gone -- are looked up by reference, and anything the lookup cannot confirm
 * goes to an admin.
 *
 * <p><b>Silence is never progress.</b> Every id asked about must come back; one that does
 * not is counted, and an order left out too many times in a row goes to an admin. So does
 * an order that shows no progress at all for too long.
 *
 * <p><b>What it changes.</b> {@link VendorStatusMap} decides the vendor order's state. The
 * order itself only moves through the state machine, with words from
 * {@link CustomerText}; an order waiting for review is left where it was and staff are
 * told. A customer with something to fix is told what, by email and in their order's
 * ticket, in those same words. The sign-in is deleted once the order is delivered.
 *
 * <p><b>When.</b> A tick every fifteen seconds asks whether a poll is due. The schedule
 * lives in the database -- the interval, its jitter, and a back-off after rate limits or
 * outages -- and a poll runs under a lock, so every instance together polls once per
 * interval.
 */
@Component
public class VendorPoller {

    private static final Logger log = LoggerFactory.getLogger(VendorPoller.class);

    /** Who the customer's timeline says moved their order. Never the partner's name. */
    static final String ACTOR = "GFS";

    private static final Set<String> TERMINAL_FOR_ORDER = Set.of("NEEDS_REVIEW", "PARTIALLY_DELIVERED");

    private final FutTransferClient client;
    private final VendorControl control;
    private final VendorOrderLedger ledger;
    private final SchedulerLock lock;
    private final OrderService orderService;
    private final OrderRepository orders;
    private final CredentialVaultService vault;
    private final NotificationService notifications;
    private final AppProperties props;

    public VendorPoller(FutTransferClient client, VendorControl control, VendorOrderLedger ledger, SchedulerLock lock,
                        OrderService orderService, OrderRepository orders, CredentialVaultService vault,
                        NotificationService notifications, AppProperties props) {
        this.client = client;
        this.control = control;
        this.ledger = ledger;
        this.lock = lock;
        this.orderService = orderService;
        this.orders = orders;
        this.vault = vault;
        this.notifications = notifications;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "PT15S", initialDelayString = "PT1M")
    public void tick() {
        if (!client.isEnabled() || control.isPaused()) return;
        try {
            lock.runExclusively("futtransfer-poll", () -> {
                if (!control.pollDue()) return;
                boolean troubled = pollOnce();
                AppProperties.FutTransferPolling p = props.futTransfer().polling();
                Duration next = control.scheduleNextPoll(troubled, p.interval(), p.jitter(), p.maxBackoff());
                if (troubled) {
                    log.warn("FUT Transfer poll troubled; next in {}s", next.toSeconds());
                }
            });
        } catch (RuntimeException e) {
            // A scheduled method that throws is silently unscheduled by some executors.
            log.error("FUT Transfer poll failed: {}", e.getMessage());
        }
    }

    /**
     * One poll of everything open.
     *
     * @return whether it was troubled -- rate limited, or the vendor unreachable -- so the
     *         next one waits longer
     */
    boolean pollOnce() {
        AppProperties.FutTransferPolling p = props.futTransfer().polling();
        boolean troubled = false;

        for (VendorOrderLedger.PollRow row : ledger.staleSubmitting(p.submittingGrace())) {
            resolveStale(row);
        }

        List<VendorOrderLedger.PollRow> open = ledger.openForPolling();
        List<VendorOrderLedger.PollRow> withId = open.stream().filter(r -> r.vendorOrderId() != null).toList();
        for (int i = 0; i < withId.size(); i += FutTransferClient.BULK_LIMIT) {
            List<VendorOrderLedger.PollRow> batch = withId.subList(i, Math.min(i + FutTransferClient.BULK_LIMIT, withId.size()));
            Map<String, String> ids = new LinkedHashMap<>();
            batch.forEach(r -> ids.put(r.vendorOrderId(), r.externalRef()));
            FutTransferClient.Read<Map<String, FutTransferClient.SupplierStatus>> read = client.statusByVendorIds(ids);
            if (read instanceof FutTransferClient.ReadFailed<Map<String, FutTransferClient.SupplierStatus>> f) {
                log.warn("FUT Transfer status for {} order(s) failed: {} ({})", batch.size(), f.error(), f.code());
                troubled |= trouble(f.error());
                if (f.error() == FutTransferClient.ReadError.AUTH) return troubled;
                continue;
            }
            Map<String, FutTransferClient.SupplierStatus> found =
                    ((FutTransferClient.ReadOk<Map<String, FutTransferClient.SupplierStatus>>) read).value();
            for (VendorOrderLedger.PollRow row : batch) {
                FutTransferClient.SupplierStatus s = found.get(row.vendorOrderId());
                if (s == null) {
                    missing(row);
                } else {
                    apply(row, s);
                }
            }
        }

        for (VendorOrderLedger.PollRow row : open) {
            if (row.vendorOrderId() != null) continue;
            FutTransferClient.Read<FutTransferClient.SupplierStatus> read = client.statusByReference(row.externalRef());
            if (read instanceof FutTransferClient.ReadOk<FutTransferClient.SupplierStatus> ok) {
                apply(row, ok.value());
            } else {
                FutTransferClient.ReadFailed<FutTransferClient.SupplierStatus> f =
                        (FutTransferClient.ReadFailed<FutTransferClient.SupplierStatus>) read;
                if (f.error() == FutTransferClient.ReadError.AUTH) return true;
                if (trouble(f.error())) {
                    troubled = true;
                } else {
                    // An answer that is not about this order counts as not mentioning it.
                    missing(row);
                }
            }
        }

        for (VendorOrderLedger.PollRow row : ledger.stalled(p.stallAfter())) {
            review(row, "STALLED", "The partner has shown no progress on this order for over "
                    + p.stallAfter().toHours() + " hours.", null);
        }
        return troubled;
    }

    private static boolean trouble(FutTransferClient.ReadError error) {
        return error == FutTransferClient.ReadError.RATE_LIMITED || error == FutTransferClient.ReadError.TRANSIENT;
    }

    /** A send whose process died mid-request: the order may exist, so it is looked up, never sent again. */
    private void resolveStale(VendorOrderLedger.PollRow row) {
        FutTransferClient.Lookup lookup = client.lookupByReference(row.externalRef(), row.amountOrderedK());
        if (lookup instanceof FutTransferClient.Found found
                && ledger.markConfirmedByLookup(row.orderId(), "STALE_SUBMITTING", found)) {
            log.info("FUT Transfer: {} was sent before a restart and is confirmed at the partner", row.externalRef());
            moveOrder(row.orderId(), VendorStatusMap.State.SUBMITTED, CustomerAction.NONE);
            return;
        }
        String code = lookup instanceof FutTransferClient.NotConfirmed nc ? nc.code() : "NOT_RECORDED";
        String reason = "This order was being sent when the application stopped, and a lookup by "
                + row.externalRef() + " could not confirm it exists (" + code + "). It may have been created. "
                + "Check the FUT Transfer dashboard before doing anything else.";
        if (ledger.markNeedsReview(row.orderId(), "STALE_SUBMITTING", reason)) {
            alert(row.externalRef(), "Needs review", reason, "STALE_SUBMITTING");
        }
    }

    /** The vendor left this order out of its answer. */
    private void missing(VendorOrderLedger.PollRow row) {
        int misses = ledger.recordMissing(row.orderId());
        int limit = props.futTransfer().polling().missingPollsBeforeReview();
        log.warn("FUT Transfer did not mention {} ({} of {})", row.externalRef(), misses, limit);
        if (misses >= limit) {
            review(row, "MISSING_FROM_POLL", "The partner has not mentioned this order in " + misses
                    + " status checks in a row.", null);
        }
    }

    /** What the vendor reported, recorded and acted on. */
    private void apply(VendorOrderLedger.PollRow row, FutTransferClient.SupplierStatus s) {
        boolean progressed = !Objects.equals(s.status(), row.vendorStatus())
                || !Objects.equals(s.amountDeliveredK(), row.deliveredK());
        ledger.recordReport(row.orderId(), s, progressed);

        VendorStatusMap.Outcome o = VendorStatusMap.map(new VendorStatusMap.Report(s.status(), s.accountCheck(),
                s.economyState(), s.amountOrderedK(), s.amountDeliveredK(), s.aborted(), s.motherOrder()), row.amountOrderedK());
        if (staleAfterRestart(row, s, o)) {
            log.info("FUT Transfer: {} still reports what it did before it was restarted; waiting",
                    row.externalRef());
            return;
        }
        String to = o.state().name();
        // Still waiting for the customer, but now for something else: a new sign-in was
        // refused for a different reason. They are asked again, for the new thing.
        boolean newAsk = o.state() == VendorStatusMap.State.AWAITING_CUSTOMER && to.equals(row.state())
                && !o.action().name().equals(row.customerAction());
        if (to.equals(row.state()) && !newAsk) return;

        String staffText = staffText(o, s, row.amountOrderedK());
        if (!ledger.move(row.orderId(), List.of(row.state()), to, o.reason(), staffText, o.action().name())) {
            return; // Something else moved it first; that decision stands.
        }
        log.info("FUT Transfer: {} {} -> {}{}", row.externalRef(), row.state(), to,
                o.reason() == null ? "" : " (" + o.reason() + ")");
        OrderEntity order = null;
        if (newAsk) {
            order = orders.findById(row.orderId()).orElse(null);
        } else if (!TERMINAL_FOR_ORDER.contains(to)) {
            order = moveOrder(row.orderId(), o.state(), o.action());
        }
        // Only while the order is on hold can the customer do anything about it: that is
        // when their order page takes a new sign-in. After an order they have already
        // re-entered details for, the old details' refusal is not news to them.
        if (o.state() == VendorStatusMap.State.AWAITING_CUSTOMER && order != null
                && order.getStatus() == OrderStatus.ON_HOLD) {
            tellCustomer(order, o.action());
        }
        if (o.alert()) {
            alert(row.externalRef(), headline(o.state()), staffText, o.reason());
        }
    }

    /**
     * Just after an admin sent a corrected sign-in or resumed the order, the vendor may
     * still be reporting what it said before. The same report again, asking the customer
     * for something, is not news until the grace has passed: it is not their new details
     * being refused.
     */
    private boolean staleAfterRestart(VendorOrderLedger.PollRow row, FutTransferClient.SupplierStatus s,
                                      VendorStatusMap.Outcome o) {
        if (o.state() != VendorStatusMap.State.AWAITING_CUSTOMER || row.resubmittedAt() == null) return false;
        if (row.resubmittedAt().isBefore(java.time.Instant.now().minus(props.futTransfer().polling().restartGrace()))) {
            return false;
        }
        return Objects.equals(s.status(), row.vendorStatus())
                && Objects.equals(s.accountCheck(), row.vendorAccountCheck())
                && Objects.equals(s.economyState(), row.vendorEconomyState());
    }

    /**
     * The customer's email and their order's ticket say what to do, in our words only.
     * Queued after the order has moved, and never able to undo it.
     */
    private void tellCustomer(OrderEntity order, CustomerAction action) {
        try {
            notifications.customerActionNeeded(new CustomerActionNotification(
                    orderService.notificationFor(order), CustomerText.forAction(action)));
        } catch (RuntimeException e) {
            log.warn("Could not queue the customer's notice for {}: {}", order.getPublicRef(), e.getMessage());
        }
    }

    private void review(VendorOrderLedger.PollRow row, String code, String reason, CustomerAction action) {
        if (ledger.move(row.orderId(), List.of("SUBMITTED", "IN_DELIVERY", "AWAITING_CUSTOMER"), "NEEDS_REVIEW",
                code, reason, action == null ? null : action.name())) {
            log.error("FULFILMENT NEEDS REVIEW: order {} ({})", row.externalRef(), code);
            alert(row.externalRef(), "Needs review", reason, code);
        }
    }

    /**
     * Moves our order to match the vendor's state, only along edges the state machine
     * allows. The reason is the customer's sentence, never the vendor's code.
     *
     * @return the order as it now is; null if it could not be found or was not allowed to move
     */
    private OrderEntity moveOrder(long orderId, VendorStatusMap.State state, CustomerAction action) {
        OrderEntity order = orders.findById(orderId).orElse(null);
        if (order == null) return null;
        List<OrderStatus> path = new ArrayList<>();
        switch (state) {
            case SUBMITTED, IN_DELIVERY -> {
                if (order.getStatus() == OrderStatus.READY_FOR_DELIVERY || order.getStatus() == OrderStatus.ON_HOLD) {
                    path.add(OrderStatus.IN_PROGRESS);
                }
            }
            case AWAITING_CUSTOMER -> {
                if (order.getStatus() == OrderStatus.IN_PROGRESS || order.getStatus() == OrderStatus.READY_FOR_DELIVERY) {
                    path.add(OrderStatus.ON_HOLD);
                }
            }
            case DELIVERED -> {
                if (order.getStatus() == OrderStatus.READY_FOR_DELIVERY || order.getStatus() == OrderStatus.ON_HOLD) {
                    path.add(OrderStatus.IN_PROGRESS);
                }
                if (order.getStatus() != OrderStatus.DELIVERED) {
                    path.add(OrderStatus.DELIVERED);
                }
            }
            default -> {
                return order;
            }
        }
        String reason = CustomerText.forState(state, action);
        for (OrderStatus next : path) {
            if (!OrderStateMachine.canTransition(order.getStatus(), next)) {
                log.warn("FUT Transfer wants order {} to go {} -> {}, which the state machine forbids",
                        order.getPublicRef(), order.getStatus(), next);
                alert(order.getPublicRef(), "Could not move the order", "The partner's report would move this order from "
                        + order.getStatus() + " to " + next + ", which is not allowed. It was left as it is.", "STATE_MACHINE");
                return null;
            }
            order = orderService.transition(order, next, Actor.SYSTEM, null, ACTOR,
                    next == OrderStatus.IN_PROGRESS && state == VendorStatusMap.State.DELIVERED
                            ? CustomerText.forState(VendorStatusMap.State.IN_DELIVERY, CustomerAction.NONE) : reason);
        }
        if (state == VendorStatusMap.State.DELIVERED) {
            // The storefront promises the sign-in is deleted once the order is done.
            vault.purge(orderId, "delivered by supplier");
        }
        return order;
    }

    private static String headline(VendorStatusMap.State state) {
        return switch (state) {
            case PARTIALLY_DELIVERED -> "Partly delivered";
            case AWAITING_CUSTOMER -> "Waiting for the customer";
            default -> "Needs review";
        };
    }

    /** For staff: the vendor's own words are fine here, and they are what an admin needs. */
    private static String staffText(VendorStatusMap.Outcome o, FutTransferClient.SupplierStatus s, long orderedK) {
        return "The partner reports status " + s.status() + ", accountCheck " + s.accountCheck() + ", economyState "
                + s.economyState() + (s.aborted() ? ", aborted" : "") + "; " + (s.amountDeliveredK() == null ? "?"
                : s.amountDeliveredK()) + "K of " + orderedK + "K delivered."
                + (o.reason() == null ? "" : " (" + o.reason() + ")");
    }

    private void alert(String ref, String headline, String detail, String code) {
        try {
            notifications.fulfilmentAlert(new FulfilmentAlert(ref, headline, detail, code,
                    props.publicUrl() + "/admin/orders/" + ref));
        } catch (RuntimeException e) {
            log.warn("Could not queue the fulfilment alert for {}: {}", ref, e.getMessage());
        }
    }
}
