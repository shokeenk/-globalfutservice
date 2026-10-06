package com.globalfutservice.payments.payop;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PayopInvoiceRepository extends JpaRepository<PayopInvoiceEntity, Long> {

    Optional<PayopInvoiceEntity> findByInvoiceId(String invoiceId);

    Optional<PayopInvoiceEntity> findByAttemptId(UUID attemptId);

    /** The order's active attempt, if any: CREATING or OPEN. */
    Optional<PayopInvoiceEntity> findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(
            Long orderId, Collection<PayopInvoiceEntity.Status> statuses);

    List<PayopInvoiceEntity> findByOrderIdOrderByCreatedAtDesc(Long orderId);

    /** Invoices opened for one order since {@code since}: the per-order limit. */
    long countByOrderIdAndCreatedAtAfter(Long orderId, Instant since);

    /** The first of them, whose leaving the window is when another may be opened. */
    @Query("select min(i.createdAt) from PayopInvoiceEntity i where i.orderId = :orderId and i.createdAt > :since")
    Optional<Instant> firstForOrderSince(@Param("orderId") Long orderId, @Param("since") Instant since);

    /** Invoices opened across one account's orders since {@code since}: the per-account limit. */
    @Query("""
            select count(i) from PayopInvoiceEntity i, com.globalfutservice.orders.OrderEntity o
            where o.id = i.orderId and o.accountId = :accountId and i.createdAt > :since
            """)
    long countForAccountSince(@Param("accountId") Long accountId, @Param("since") Instant since);

    @Query("""
            select min(i.createdAt) from PayopInvoiceEntity i, com.globalfutservice.orders.OrderEntity o
            where o.id = i.orderId and o.accountId = :accountId and i.createdAt > :since
            """)
    Optional<Instant> firstForAccountSince(@Param("accountId") Long accountId, @Param("since") Instant since);

    /**
     * When the order's last Payop invoice stops being payable, if one still is: an invoice
     * Payop holds, or one being created, until its 24 hours are up. Payop cannot cancel an
     * invoice, so whatever its status here, it can be paid until then.
     */
    @Query("""
            select max(i.expiresAt) from PayopInvoiceEntity i
            where i.orderId = :orderId and i.expiresAt > :now
              and (i.invoiceId is not null or i.status = :creating)
            """)
    Optional<Instant> payableUntil(@Param("orderId") Long orderId, @Param("now") Instant now,
                                   @Param("creating") PayopInvoiceEntity.Status creating);

    /** Open invoices whose 24 hours are up: the same instant {@link #payableUntil} stops counting them. */
    List<PayopInvoiceEntity> findByStatusAndExpiresAtLessThanEqual(PayopInvoiceEntity.Status status, Instant now);

    /** Attempts whose creation never finished: the request to Payop was interrupted. */
    List<PayopInvoiceEntity> findByStatusAndUpdatedAtBefore(PayopInvoiceEntity.Status status, Instant before);

    List<PayopInvoiceEntity> findByStatusInOrderByUpdatedAtDesc(Collection<PayopInvoiceEntity.Status> statuses,
                                                               Pageable page);

    List<PayopInvoiceEntity> findAllByOrderByCreatedAtDesc(Pageable page);

    long countByStatusIn(Collection<PayopInvoiceEntity.Status> statuses);
}
