package com.globalfutservice.fulfilment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.notify.FulfilmentAlert;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.scheduling.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The one settings update the deploy of 2026-10-15 sends: minTransferAmount 0 to every open
 * public-pool order FUT Transfer has that was sent with the old 50.
 *
 * <p>V55 marks those orders PENDING when it runs; nothing else ever does, so orders sent from
 * then on are never touched. Each is claimed before its call and never sent the update
 * automatically again: at most once per order. The call is VendorOrderActions' own, so it goes
 * in the order's call log and on its admin timeline like an admin's press of the button; the
 * orders it touched are listed in the log and to staff.
 *
 * <p>Nothing is claimed while FUT Transfer is off or calls are paused: the orders wait for the
 * next run. A "wait" answer (429) puts the order back and ends the run; it is tried again on
 * the next one.
 */
@Component
public class PartnerSettingsUpdate {

    private static final Logger log = LoggerFactory.getLogger(PartnerSettingsUpdate.class);

    /** The prefix every summary line carries, so one log search finds it. */
    static final String LOG_PREFIX = "FUT TRANSFER SETTINGS UPDATE:";

    private final VendorOrderLedger ledger;
    private final VendorOrderActions actions;
    private final FutTransferClient client;
    private final VendorControl control;
    private final OrderRepository orders;
    private final NotificationService notifications;
    private final SchedulerLock lock;
    private final AppProperties props;
    private Duration pause = Duration.ofSeconds(1);

    public PartnerSettingsUpdate(VendorOrderLedger ledger, VendorOrderActions actions, FutTransferClient client,
                                 VendorControl control, OrderRepository orders, NotificationService notifications,
                                 SchedulerLock lock, AppProperties props) {
        this.ledger = ledger;
        this.actions = actions;
        this.client = client;
        this.control = control;
        this.orders = orders;
        this.notifications = notifications;
        this.lock = lock;
        this.props = props;
    }

    /** No pause between orders: for tests. */
    PartnerSettingsUpdate withoutPauses() {
        this.pause = Duration.ZERO;
        return this;
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    public void scheduled() {
        try {
            lock.runExclusively("fut-settings-update", this::run);
        } catch (RuntimeException e) {
            log.error("FUT Transfer settings update run failed: {}", e.getClass().getSimpleName());
        }
    }

    /** One pass over the orders still due. @return what each order it touched got, as listed */
    List<String> run() {
        if (!client.isEnabled() || control.isPaused()) return List.of();
        List<Long> due = ledger.settingsUpdatesDue();
        if (due.isEmpty()) return List.of();

        List<String> touched = new ArrayList<>();
        List<String> needPerson = new ArrayList<>();
        for (Long orderId : due) {
            if (!ledger.claimSettingsUpdate(orderId)) continue;
            OrderEntity order = orders.findById(orderId).orElse(null);
            if (order == null) {
                ledger.settleSettingsUpdate(orderId, "SKIPPED");
                continue;
            }
            String ref = order.getPublicRef();
            VendorOrderActions.Result r = actions.updateSettings(order, VendorOrderActions.DEPLOY);
            if (r.status() == VendorOrderActions.Status.REFUSED && VendorOrderActions.RATE_LIMITED.equals(r.code())) {
                ledger.settleSettingsUpdate(orderId, "PENDING");
                touched.add(ref + " asked to wait, tried again on the next run");
                break;
            }
            if (r.status() == VendorOrderActions.Status.NOT_SENT && (!client.isEnabled() || control.isPaused())) {
                ledger.settleSettingsUpdate(orderId, "PENDING");
                break;
            }
            String outcome = switch (r.status()) {
                case DONE -> FutTransferClient.IGNORED.equals(r.code()) ? "IGNORED" : "DONE";
                case REFUSED -> "REFUSED";
                case UNCERTAIN -> "UNCERTAIN";
                case NOT_SENT -> "SKIPPED";
            };
            ledger.settleSettingsUpdate(orderId, outcome);
            touched.add(ref + " " + outcome + (r.code() == null ? "" : " (" + r.code() + ")"));
            if (r.status() == VendorOrderActions.Status.REFUSED || r.status() == VendorOrderActions.Status.UNCERTAIN) {
                needPerson.add(ref);
            }
            pause();
        }

        if (!touched.isEmpty()) {
            int min = props.futTransfer().order().minTransferAmount();
            log.info("{} minTransferAmount {} sent to {} order(s): {}", LOG_PREFIX, min, touched.size(),
                    String.join("; ", touched));
            alert(min, touched, needPerson);
        }
        return touched;
    }

    private void alert(int min, List<String> touched, List<String> needPerson) {
        try {
            String detail = "minTransferAmount " + min + " was sent to " + touched.size()
                    + " order(s) at FUT Transfer, once each, and nothing else was changed: " + String.join("; ", touched)
                    + "." + (needPerson.isEmpty() ? "" : " Not confirmed for " + String.join(", ", needPerson)
                    + ": use \"Update settings at partner\" on those orders.");
            notifications.fulfilmentAlert(new FulfilmentAlert("FUT Transfer", "Settings updated on deploy", detail,
                    needPerson.isEmpty() ? null : "CHECK_ORDERS", props.publicUrl() + "/admin/orders"));
        } catch (RuntimeException e) {
            log.warn("Could not queue the settings-update summary: {}", e.getClass().getSimpleName());
        }
    }

    private void pause() {
        if (pause.isZero()) return;
        try {
            Thread.sleep(pause.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
