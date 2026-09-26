package com.globalfutservice.coaching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Run something once the current transaction has committed, and never let it fail that
 * transaction.
 *
 * <p>Used for the coaching hold's side effects on the payment and order paths: confirming
 * the hold when an order is paid, extending it when proof is submitted, releasing it when
 * a payment is rejected or an order abandoned. Each of those is secondary to the change
 * that triggers it. Run inside the payment's own transaction, a hold that failed to
 * confirm -- say it raced the expiry sweep -- would roll back the payment approval itself,
 * and approving a payment must not depend on a calendar. Run afterwards, a failure leaves
 * the hold as it was, the expiry sweep releases it in due course, and the customer still
 * has the credit the payment granted.
 *
 * <p>The action must reach its work through a Spring bean, so it gets a transaction of
 * its own: there is none left by the time it runs.
 */
public final class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {
    }

    /**
     * @param what a few words for the log line should the action fail
     */
    public static void run(String what, Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safely(what, action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safely(what, action);
            }
        });
    }

    private static void safely(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Could not {}: {}", what, e.toString());
        }
    }
}
