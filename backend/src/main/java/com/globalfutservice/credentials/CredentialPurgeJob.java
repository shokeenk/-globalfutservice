package com.globalfutservice.credentials;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Enforces the retention promise on a timer.
 *
 * <p>The privacy policy says EA details are deleted once an order is done. That promise
 * is kept twice over: the order state machine purges on entry to a terminal state, and
 * this job purges anything past its window regardless of state. The second mechanism
 * exists because the first one depends on code being correct, and this one only depends
 * on the clock.
 */
@Component
public class CredentialPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(CredentialPurgeJob.class);

    private final CredentialVaultService vaultService;

    public CredentialPurgeJob(CredentialVaultService vaultService) {
        this.vaultService = vaultService;
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    public void sweep() {
        try {
            vaultService.purgeExpired();
        } catch (Exception e) {
            log.error("Credential retention sweep failed", e);
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
