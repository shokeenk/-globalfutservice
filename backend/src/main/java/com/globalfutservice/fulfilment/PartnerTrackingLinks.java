package com.globalfutservice.fulfilment;

import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The customer's tracking link from FUT Transfer: the page where they watch their coins arrive
 * and can correct their EA sign-in.
 *
 * <p><b>Apart from the order, always.</b> It is asked for right after the partner's id for the
 * order has been committed -- never inside placing it -- and nothing that happens here can
 * change the order, its vendor state, or whether it is sent: no failure state, no resend, no
 * touch on the exactly-once claim. A link that could not be had is asked for again by the
 * poller, with growing gaps, until it is stored or the order is finished. A refusal because
 * the order is finished is recorded as unavailable, and nothing more is asked.
 *
 * <p><b>A secret.</b> Whoever holds the link can change the customer's sign-in. It is kept
 * verbatim, shown only to the order's owner -- their order page and one email -- and never
 * logged, written to the call log or posted to Discord.
 */
@Component
public class PartnerTrackingLinks {

    private static final Logger log = LoggerFactory.getLogger(PartnerTrackingLinks.class);

    static final int BATCH = 20;
    static final Duration FIRST_RETRY = Duration.ofMinutes(1);
    static final Duration MAX_RETRY = Duration.ofMinutes(60);

    /** Past the point where "your coins are on the way" is news. */
    private static final Set<OrderStatus> FINISHED = EnumSet.of(OrderStatus.DELIVERED, OrderStatus.COMPLETED,
            OrderStatus.REFUNDED, OrderStatus.CREDITED, OrderStatus.ABANDONED, OrderStatus.DISPUTED);

    /** What asking for one order's link came to. */
    public enum Outcome { STORED, UNAVAILABLE, RETRY_LATER, NOT_LINKABLE }

    private final FutTransferClient client;
    private final VendorOrderLedger ledger;
    private final OrderRepository orders;
    private final OrderService orderService;
    private final NotificationService notifications;
    private final Clock clock;

    private PartnerLinkVisibility partnerLinks = PartnerLinkVisibility.SHOWN;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPartnerLinks(PartnerLinkVisibility partnerLinks) {
        this.partnerLinks = partnerLinks;
    }

    public PartnerTrackingLinks(FutTransferClient client, VendorOrderLedger ledger, OrderRepository orders,
                                OrderService orderService, NotificationService notifications, Clock clock) {
        this.client = client;
        this.ledger = ledger;
        this.orders = orders;
        this.orderService = orderService;
        this.notifications = notifications;
        this.clock = clock;
    }

    /**
     * The partner has just accepted the order and its id is committed: ask for the link now,
     * while the order cannot yet be finished. Never throws -- the order is already sent, and
     * nothing here may suggest otherwise to the caller.
     */
    public void afterAccepted(long orderId) {
        try {
            ledger.linkable(orderId).ifPresent(this::ask);
            sendEmail(orderId);
        } catch (RuntimeException e) {
            log.warn("Tracking link for order {} not asked for now: {}; the poller will ask", orderId,
                    e.getClass().getSimpleName());
        }
    }

    /**
     * One pass, on the poller's schedule: every link that is due is asked for, and every link
     * stored without its email yet is emailed. Orders sent before links existed are picked up
     * here too, which is their backfill.
     *
     * @return how many links were stored
     */
    public int sweep() {
        int stored = 0;
        for (VendorOrderLedger.LinkRow row : ledger.linksDue(BATCH)) {
            try {
                if (ask(row) == Outcome.STORED) stored++;
            } catch (RuntimeException e) {
                log.warn("Tracking link for {} failed: {}", row.externalRef(), e.getClass().getSimpleName());
            }
        }
        for (Long orderId : ledger.trackingEmailsDue(BATCH)) {
            sendEmail(orderId);
        }
        return stored;
    }

    /** An admin's "Generate link": asked for now, due or not, if the order can have one. */
    public Outcome generateNow(long orderId) {
        Optional<VendorOrderLedger.LinkRow> row = ledger.linkable(orderId);
        if (row.isEmpty()) return Outcome.NOT_LINKABLE;
        Outcome outcome = ask(row.get());
        sendEmail(orderId);
        return outcome;
    }

    private Outcome ask(VendorOrderLedger.LinkRow row) {
        return switch (client.trackingLink(row.vendorOrderId(), row.externalRef())) {
            case FutTransferClient.LinkReady r -> {
                VendorOrderLedger.LinkStored stored = ledger.storeTrackingLink(row.orderId(), r);
                log.info("Tracking link stored for {}{}", row.externalRef(), stored.startedEmailNotNeeded()
                        ? "; within " + TransferStartedNotices.LINK_GRACE.toMinutes()
                                + " min of the transfer starting, so only \"your coins are on the way\" is sent"
                        : "");
                yield Outcome.STORED;
            }
            case FutTransferClient.LinkUnavailable u -> {
                ledger.markTrackingUnavailable(row.orderId(), u.code());
                log.info("Tracking link unavailable for {} ({}); not asked for again", row.externalRef(), u.code());
                yield Outcome.UNAVAILABLE;
            }
            case FutTransferClient.LinkFailed f -> {
                Duration wait = backoff(row.attempts() + 1);
                ledger.trackingRetryLater(row.orderId(), f.code(), clock.instant().plus(wait));
                log.info("Tracking link for {} not received ({}); asking again in {} min", row.externalRef(),
                        f.code(), wait.toMinutes());
                yield Outcome.RETRY_LATER;
            }
        };
    }

    /** 1, 2, 4, 8... minutes after each try, at most an hour. */
    static Duration backoff(int attempt) {
        int doublings = Math.min(Math.max(attempt, 1) - 1, 6);
        Duration wait = FIRST_RETRY.multipliedBy(1L << doublings);
        return wait.compareTo(MAX_RETRY) > 0 ? MAX_RETRY : wait;
    }

    /**
     * "Your coins are on the way", with the link: claimed before it is sent, so at most once
     * per order whoever gets here first and however often. Not before the "transfer started"
     * email is settled: a link that comes after that window waits for it, and goes on a later
     * sweep. Not sent for an order already finished; a failure to send is logged and left.
     */
    private void sendEmail(long orderId) {
        Optional<String> link = ledger.claimTrackingEmail(orderId);
        if (link.isEmpty()) return;
        try {
            OrderEntity order = orders.findById(orderId).orElse(null);
            if (order == null || !order.getSku().isCoinTransfer() || FINISHED.contains(order.getStatus())) {
                return;
            }
            // The partner's link, in the configured style, only when customers are given it;
            // otherwise our own page.
            String url = ledger.partnerLink(orderId).map(partnerLinks::customerUrl).orElse(null);
            notifications.coinsOnTheWay(orderService.notificationFor(order), url);
            log.info("Order {}: \"your coins are on the way\" sent", order.getPublicRef());
        } catch (RuntimeException e) {
            log.warn("Could not send \"your coins are on the way\" for order {}: {}", orderId,
                    e.getClass().getSimpleName());
        }
    }
}
