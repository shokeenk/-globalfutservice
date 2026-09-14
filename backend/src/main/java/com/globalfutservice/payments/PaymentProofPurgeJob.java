package com.globalfutservice.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Deletes payment screenshots once they are past their retention window.
 *
 * <p>These images are the most personal thing the system stores that is not in the vault:
 * a banking app's confirmation screen routinely shows a full name, a balance and the last
 * few transactions. They are needed while a payment is being checked and while anything
 * about the order can still be argued, and not after -- so they were kept forever only
 * because nothing had been written to remove them.
 *
 * <p>Clock-driven, like the credential sweep, rather than hung off order transitions: an
 * order that stalls in some state nobody closes still loses its screenshot on time. The one
 * exception is an order under dispute or on hold, where the image may be the evidence
 * somebody is about to need; see {@link ManualPaymentProofRepository#deleteExpired}.
 *
 * <p>This removes the database copy only. The copy posted to Discord lives in Discord and
 * has to be cleared there.
 */
@Component
public class PaymentProofPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentProofPurgeJob.class);

    private final ManualPaymentService manualPayments;

    public PaymentProofPurgeJob(ManualPaymentService manualPayments) {
        this.manualPayments = manualPayments;
    }

    /**
     * Every six hours. The window is measured in days, so running more often buys nothing
     * but queries; running only daily would let a restart-heavy week skip a sweep.
     */
    @Scheduled(fixedDelayString = "PT6H",
            initialDelayString = "${gfs.fulfilment.proof-purge-initial-delay:PT3M}")
    public void sweep() {
        try {
            manualPayments.purgeExpiredProofs(Instant.now());
        } catch (Exception e) {
            log.error("Payment screenshot retention sweep failed", e);
        }
    }
}
