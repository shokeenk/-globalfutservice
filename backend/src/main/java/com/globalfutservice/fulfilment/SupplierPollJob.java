package com.globalfutservice.fulfilment;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.orders.Actor;
import com.globalfutservice.domain.orders.OrderStateMachine;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.domain.orders.SupplierStatusMapper;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps our view of an order in step with the supplier's.
 *
 * <p><b>Separate from {@link SupplierFulfilmentService} for a structural reason.</b>
 * Polling has to move orders through the state machine, which means calling
 * {@link OrderService}; dispatch is called <em>by</em> the order flow. Putting both on one
 * bean would make {@code OrderService} and the supplier depend on each other, and the
 * usual fix — {@code @Lazy} on one side — hides the cycle rather than removing it. Two
 * beans, each with one direction of dependency, needs no annotation to explain it.
 *
 * <p><b>A failed read changes nothing.</b> Every transition here comes from a status the
 * supplier actually returned. When the call fails, orders keep the state they had and the
 * next tick tries again — because the alternative, inferring progress from silence, is how
 * a paid order becomes DELIVERED during an outage.
 */
@Component
public class SupplierPollJob {

    private static final Logger log = LoggerFactory.getLogger(SupplierPollJob.class);

    private final FutTransferClient client;
    private final OrderService orderService;
    private final OrderRepository orders;
    private final CredentialVaultService vault;
    private final Clock clock;

    public SupplierPollJob(FutTransferClient client, OrderService orderService,
                           OrderRepository orders, CredentialVaultService vault, Clock clock) {
        this.client = client;
        this.orderService = orderService;
        this.orders = orders;
        this.vault = vault;
        this.clock = clock;
    }

    /**
     * Sixty seconds, with a minute of grace at boot.
     *
     * <p>Fixed rather than read from configuration: {@code @Scheduled} needs a constant,
     * and a coin transfer takes minutes to hours — a faster poll would spend the
     * supplier's rate limit to learn nothing.
     */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void poll() {
        if (!client.isEnabled()) return;
        try {
            int changed = pollOpenOrders();
            if (changed > 0) log.info("Supplier poll moved {} order(s)", changed);
        } catch (RuntimeException e) {
            // A scheduled method that throws is silently unscheduled by some executors.
            log.error("Supplier poll failed: {}", e.getMessage());
        }
    }

    @Transactional
    public int pollOpenOrders() {
        List<OrderEntity> open = orders.findOpenSupplierOrders();
        if (open.isEmpty()) return 0;

        int changed = 0;
        for (int i = 0; i < open.size(); i += FutTransferClient.BULK_LIMIT) {
            changed += pollBatch(open.subList(i,
                    Math.min(i + FutTransferClient.BULK_LIMIT, open.size())));
        }
        return changed;
    }

    private int pollBatch(List<OrderEntity> batch) {
        List<String> refs = batch.stream().map(OrderEntity::getPublicRef).toList();
        List<FutTransferClient.SupplierStatus> results;
        try {
            results = client.statusBulk(refs);
        } catch (RuntimeException e) {
            log.warn("Supplier status poll failed for {} order(s): {}", refs.size(), e.getMessage());
            return 0;
        }

        Instant now = clock.instant();
        int changed = 0;
        for (FutTransferClient.SupplierStatus s : results) {
            OrderEntity order = batch.stream()
                    .filter(o -> o.getPublicRef().equals(s.orderRef()))
                    .findFirst().orElse(null);
            if (order == null) continue;

            order.recordSupplierStatus(s.status(), s.accountCheck(), s.economyState(),
                    s.amountOrdered(), s.amountDelivered(), now);
            orders.save(order);

            SupplierStatusMapper.Outcome outcome = SupplierStatusMapper.map(
                    order.getStatus(), s.status(), s.accountCheck(), s.economyState(), s.aborted());

            if (outcome.status() == order.getStatus()) {
                if (outcome.needsOperator()) {
                    log.warn("Order {} needs an operator — status={} accountCheck={} economyState={}",
                            order.getPublicRef(), s.status(), s.accountCheck(), s.economyState());
                }
                continue;
            }

            /*
             * Asked before it is attempted. The mapper knows what the supplier means but
             * not what this order is allowed to do next, and a supplier that reports
             * `finished` on an order we already refunded must not reopen it.
             */
            if (!OrderStateMachine.canTransition(order.getStatus(), outcome.status())) {
                log.warn("Supplier wants {} -> {} for order {}, which the state machine forbids",
                        order.getStatus(), outcome.status(), order.getPublicRef());
                continue;
            }

            orderService.transition(order, outcome.status(), Actor.SYSTEM, null,
                    "futtransfer", reasonFor(outcome, s));
            changed++;

            if (outcome.status() == OrderStatus.DELIVERED) {
                /*
                 * The storefront promises the sign-in is destroyed when the order is done.
                 * The retention sweep would reach it within the day; purging here is what
                 * makes the sentence literally true.
                 */
                vault.purge(order.getId(), "delivered by supplier");
            }
        }
        return changed;
    }

    /** Written for the order timeline, which the customer can read. */
    private static String reasonFor(SupplierStatusMapper.Outcome outcome,
                                    FutTransferClient.SupplierStatus s) {
        if (outcome.action() == SupplierStatusMapper.CustomerAction.NONE) {
            return "Supplier reports " + s.status();
        }
        return "Supplier reports " + s.status() + " — " + outcome.action();
    }
}
