package com.globalfutservice.coaching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Run something once the current transaction has committed, in a transaction of its own,
 * and never let it fail the transaction that triggered it.
 *
 * <p>Used for the coaching hold's side effects on the payment and order paths: confirming
 * the hold when an order is paid, extending it when proof is submitted, releasing it when
 * a payment is rejected or an order abandoned. Each is secondary to the change that
 * triggers it. Run inside the payment's own transaction, a hold that failed to confirm --
 * say it raced the expiry sweep -- would roll back the payment approval itself, and
 * approving a payment must not depend on a calendar. Run afterwards, a failure leaves the
 * hold as it was, the expiry sweep releases it in due course, and the customer still has
 * the credit the payment granted.
 *
 * <p><b>Why a new transaction, explicitly.</b> Code running in an {@code afterCommit}
 * callback still participates in the transaction that has just committed, so a
 * {@code @Transactional} method called from there joins it -- and no second commit ever
 * follows. Its changes are silently discarded. That is what happened before this ran the
 * action through a {@code REQUIRES_NEW} template: found against the dev database, where
 * the log said a paid order's hold had moved PENDING -> SCHEDULED and the row still said
 * PENDING, with no credit spent.
 */
@Component
public class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    /** Null only for {@link #immediate()}, which unit tests use in place of a database. */
    private final TransactionTemplate requiresNew;

    @Autowired
    public AfterCommit(PlatformTransactionManager transactions) {
        this.requiresNew = new TransactionTemplate(transactions);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private AfterCommit() {
        this.requiresNew = null;
    }

    /**
     * Runs every action at once, with no transaction of its own. For unit tests that
     * construct services directly and have no transaction to wait for.
     */
    public static AfterCommit immediate() {
        return new AfterCommit();
    }

    /**
     * @param what a few words for the log line should the action fail
     */
    public void run(String what, Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safely(what, () -> inOwnTransaction(action));
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safely(what, () -> inOwnTransaction(action));
            }
        });
    }

    private void inOwnTransaction(Runnable action) {
        if (requiresNew == null) {
            action.run();
        } else {
            requiresNew.executeWithoutResult(status -> action.run());
        }
    }

    private static void safely(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Could not {}: {}", what, e.toString());
        }
    }
}
