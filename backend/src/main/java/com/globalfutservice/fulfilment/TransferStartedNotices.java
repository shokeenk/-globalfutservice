package com.globalfutservice.fulfilment;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.scheduling.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * "Your coin transfer has started": emailed once, when FUT Transfer first accepts a coin order.
 *
 * <p>Every way the partner can come to have an order -- an admin's Approve, automatic sending,
 * a lookup confirming it after a lost answer, an admin linking it -- sets
 * {@code orders.transfer_started_at}, in the vendor ledger. This sweeps for orders with that
 * set and no notice yet, so one place serves them all, whichever way it happened.
 *
 * <p>At most once: each order's {@code transfer_notice_at} is claimed before anything is sent,
 * and only the run that claimed it sends. The email itself goes off this thread, and anything
 * that goes wrong with it is logged and left -- it never touches the order. An order already
 * finished by the time it is seen is not told its transfer has started.
 */
@Component
public class TransferStartedNotices {

    private static final Logger log = LoggerFactory.getLogger(TransferStartedNotices.class);
    private static final int BATCH = 50;

    /** Past the point where "your transfer has started" is news. */
    private static final Set<OrderStatus> FINISHED = EnumSet.of(OrderStatus.DELIVERED, OrderStatus.COMPLETED,
            OrderStatus.REFUNDED, OrderStatus.CREDITED, OrderStatus.ABANDONED, OrderStatus.DISPUTED);

    private final NamedParameterJdbcTemplate jdbc;
    private final OrderRepository orders;
    private final OrderService orderService;
    private final NotificationService notifications;
    private final SchedulerLock lock;

    public TransferStartedNotices(NamedParameterJdbcTemplate jdbc, OrderRepository orders, OrderService orderService,
                                  NotificationService notifications, SchedulerLock lock) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.orderService = orderService;
        this.notifications = notifications;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT45S")
    public void scheduled() {
        try {
            lock.runExclusively("transfer-started-notices", this::sendDue);
        } catch (RuntimeException e) {
            log.error("Transfer-started notices failed: {}", e.getClass().getSimpleName());
        }
    }

    /** @return how many emails were handed to the notifiers */
    public int sendDue() {
        List<Long> due = jdbc.queryForList("""
                select id from orders
                 where transfer_started_at is not null and transfer_notice_at is null
                 order by transfer_started_at, id
                 limit :limit
                """, new MapSqlParameterSource("limit", BATCH), Long.class);
        int sent = 0;
        for (Long orderId : due) {
            // Claimed first, so it is sent at most once whatever happens after this line.
            int claimed = jdbc.update("""
                    update orders set transfer_notice_at = now() where id = :id and transfer_notice_at is null
                    """, new MapSqlParameterSource("id", orderId));
            if (claimed != 1) {
                continue;
            }
            try {
                OrderEntity order = orders.findById(orderId).orElse(null);
                if (order == null || !order.getSku().isCoinTransfer() || FINISHED.contains(order.getStatus())) {
                    continue;
                }
                notifications.transferStarted(orderService.notificationFor(order));
                log.info("Order {}: \"your coin transfer has started\" sent", order.getPublicRef());
                sent++;
            } catch (RuntimeException e) {
                // The email is a courtesy; the order is untouched by it either way.
                log.warn("Could not send the transfer-started email for order {}: {}", orderId,
                        e.getClass().getSimpleName());
            }
        }
        return sent;
    }
}
