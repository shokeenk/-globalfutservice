package com.globalfutservice.orders.result;

import com.globalfutservice.scheduling.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * The choice window has closed: applies the default to every settlement nobody chose.
 *
 * <p>With the default of GFS Store Credit, the credit is added and the settlement marked
 * Issued ("issued automatically after 5 days"), and the settlement email goes out. A
 * settlement whose method was already set -- by the customer or an admin -- is never
 * touched: the job only looks at ones still waiting for a choice.
 *
 * <p>One runner at a time across instances, and each order in its own transaction, so one
 * that fails is retried on the next run without holding up the others.
 */
@Component
public class SettlementDefaultJob {

    private static final Logger log = LoggerFactory.getLogger(SettlementDefaultJob.class);

    private final OrderResultRepository results;
    private final OrderResultService service;
    private final ResultRules rules;
    private final SchedulerLock lock;
    private final Clock clock;

    public SettlementDefaultJob(OrderResultRepository results, OrderResultService service, ResultRules rules,
                                SchedulerLock lock, Clock clock) {
        this.results = results;
        this.service = service;
        this.rules = rules;
        this.lock = lock;
        this.clock = clock;
    }

    @Scheduled(cron = "0 */10 * * * *")
    public void run() {
        if (!rules.enabled()) {
            return;
        }
        lock.runExclusively("champs-settlement-default", this::sweep);
    }

    /** One pass: every settlement whose window has closed with nothing chosen. */
    public int sweep() {
        List<Long> due = results.findDue(clock.instant());
        int applied = 0;
        for (Long orderId : due) {
            try {
                if (service.applyDefault(orderId)) {
                    applied++;
                }
            } catch (RuntimeException e) {
                log.warn("Settlement default for order id {} failed; retried next run: {}", orderId, e.getMessage());
            }
        }
        if (applied > 0) {
            log.info("Settlement default applied to {} order(s)", applied);
        }
        return applied;
    }
}
