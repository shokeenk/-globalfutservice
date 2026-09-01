package com.globalfutservice.orders.jobs;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.orders.Actor;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * The two clocks the order lifecycle depends on.
 *
 * <p><b>Deployment note.</b> These run on every instance. Behind more than one replica,
 * either pin them to a single node or add a lock — ShedLock over the same Postgres is the
 * least-effort option. Two instances settling the same guarantee simultaneously would be
 * caught by the idempotency guards on points and commission, but relying on that as the
 * primary control is not a design, it is a hope.
 */
@Component
public class OrderLifecycleJobs {

    private static final Logger log = LoggerFactory.getLogger(OrderLifecycleJobs.class);

    private final OrderRepository orders;
    private final OrderService orderService;
    private final AppProperties props;
    private final Clock clock;

    public OrderLifecycleJobs(OrderRepository orders, OrderService orderService,
                              AppProperties props, Clock clock) {
        this.orders = orders;
        this.orderService = orderService;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Settles orders whose seven-day guarantee window has elapsed.
     *
     * <p>This is the transition that grants loyalty points, accrues affiliate commission
     * and purges the credential vault. Deferring all three to here — rather than doing
     * them at payment — is what removes the need for clawback logic on the refund path.
     */
    @Scheduled(cron = "0 15 * * * *")
    public void settleGuarantees() {
        try {
            List<OrderEntity> due = orders.findGuaranteeElapsed(clock.instant());
            for (OrderEntity order : due) {
                try {
                    orderService.transition(order, OrderStatus.COMPLETED, Actor.SYSTEM, null,
                            "scheduler", "Guarantee window elapsed without a claim");
                } catch (Exception e) {
                    // One bad order must not stop the batch.
                    log.error("Could not settle order {}", order.getPublicRef(), e);
                }
            }
            if (!due.isEmpty()) {
                log.info("Settled {} order(s) past their guarantee window", due.size());
            }
        } catch (Exception e) {
            log.error("Guarantee settlement sweep failed", e);
        }
    }

    /**
     * Closes checkouts that were never paid.
     *
     * <p>The cutoff is deliberately generous — well beyond the contractual delivery
     * window. People start a checkout, go and find their card, and come back twenty
     * minutes later; cancelling under them is the most irritating thing a storefront can
     * do, and an abandoned row costs nothing to keep for a day.
     */
    @Scheduled(cron = "0 45 * * * *")
    public void sweepAbandoned() {
        try {
            Instant cutoff = clock.instant().minus(props.fulfilment().deliverySla());
            List<OrderEntity> stale = orders.findStaleUnpaid(cutoff);
            for (OrderEntity order : stale) {
                try {
                    orderService.transition(order, OrderStatus.ABANDONED, Actor.SYSTEM, null,
                            "scheduler", "No payment received");
                } catch (Exception e) {
                    log.warn("Could not abandon order {}: {}", order.getPublicRef(), e.getMessage());
                }
            }
            if (!stale.isEmpty()) {
                log.info("Marked {} unpaid checkout(s) abandoned", stale.size());
            }
        } catch (Exception e) {
            log.error("Abandoned-checkout sweep failed", e);
        }
    }
}
