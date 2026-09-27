package com.globalfutservice.coaching;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The follow-up runs after the commit, in a transaction of its own that really commits.
 *
 * <p>Joining the finished transaction instead is the failure this guards against: the
 * work runs, logs as done, and is discarded because no commit follows.
 */
class AfterCommitTest {

    private PlatformTransactionManager transactions;
    private TransactionStatus status;
    private AfterCommit afterCommit;

    @BeforeEach
    void setUp() {
        transactions = mock(PlatformTransactionManager.class);
        status = new SimpleTransactionStatus();
        when(transactions.getTransaction(any())).thenReturn(status);
        afterCommit = new AfterCommit(transactions);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    @DisplayName("waits for the commit, then runs in a REQUIRES_NEW transaction that is committed")
    void newTransactionAfterCommit() {
        List<String> ran = new ArrayList<>();

        afterCommit.run("confirm a hold", () -> ran.add("confirmed"));
        assertThat(ran).isEmpty();

        TransactionSynchronizationUtils.triggerAfterCommit();

        assertThat(ran).containsExactly("confirmed");
        verify(transactions).getTransaction(argThat(d ->
                d.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        verify(transactions).commit(status);
    }

    @Test
    @DisplayName("a failing follow-up is rolled back and never thrown back")
    void failureContained() {
        afterCommit.run("confirm a hold", () -> {
            throw new IllegalStateException("optimistic lock");
        });

        assertThatCode(TransactionSynchronizationUtils::triggerAfterCommit).doesNotThrowAnyException();
        verify(transactions).rollback(status);
        verify(transactions, never()).commit(any());
    }

    @Test
    @DisplayName("with no transaction to wait for, it runs straight away -- still in its own")
    void noTransaction() {
        TransactionSynchronizationManager.clearSynchronization();
        List<String> ran = new ArrayList<>();

        afterCommit.run("confirm a hold", () -> ran.add("confirmed"));

        assertThat(ran).containsExactly("confirmed");
        verify(transactions).commit(status);
        TransactionSynchronizationManager.initSynchronization();
    }
}
