package com.globalfutservice.credentials;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.fulfilment.VendorOrderLedger;
import com.globalfutservice.scheduling.SchedulerLock;

/**
 * Enforces the retention promise on a timer.
 *
 * <p>The privacy policy says EA details are deleted once an order is done. That promise
 * is kept twice over: the order state machine purges on entry to a terminal state, and
 * this job purges anything past its window regardless of state. The second mechanism
 * exists because the first one depends on code being correct, and this one only depends
 * on the clock.
 *
 * <p>It also deletes the sign-in of an order parked for an admin's review once nobody has
 * touched it for {@code gfs.fut-transfer.review-credential-retention}: an order that waits
 * that long is not going to be sent again on the same details.
 */
@Component
public class CredentialPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(CredentialPurgeJob.class);

    private final CredentialVaultService vaultService;
    private final SchedulerLock lock;
    private final VendorOrderLedger vendorOrders;
    private final AppProperties props;

    public CredentialPurgeJob(CredentialVaultService vaultService, SchedulerLock lock,
                              VendorOrderLedger vendorOrders, AppProperties props) {
        this.vaultService = vaultService;
        this.lock = lock;
        this.vendorOrders = vendorOrders;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    public void sweep() {
        try {
            // One sweeper across every instance.
            lock.runExclusively("credential-purge", () -> {
                vaultService.purgeExpired();
                purgeHeldForReview();
            });
        } catch (Exception e) {
            log.error("Credential retention sweep failed", e);
        }
    }

    void purgeHeldForReview() {
        for (long orderId : vendorOrders.heldForReviewLongerThan(props.futTransfer().reviewCredentialRetention())) {
            vaultService.purge(orderId, "held for review past retention");
        }
    }

    /**
     * Hourly reminder of how many sign-ins are currently held. A number that climbs
     * steadily means orders are getting stuck somewhere before delivery — the metric is
     * a cheap early warning for a fulfilment backlog.
     */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT2M")
    public void report() {
        long held = vaultService.countHeld();
        if (held > 0) {
            log.info("Credential vault currently holds {} un-purged record(s)", held);
        }
    }
}
