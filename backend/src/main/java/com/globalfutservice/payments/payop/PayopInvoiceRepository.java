package com.globalfutservice.payments.payop;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayopInvoiceRepository extends JpaRepository<PayopInvoiceEntity, Long> {

    Optional<PayopInvoiceEntity> findByInvoiceId(String invoiceId);

    Optional<PayopInvoiceEntity> findByAttemptId(UUID attemptId);

    /** The order's active attempt, if any: CREATING or OPEN. */
    Optional<PayopInvoiceEntity> findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(
            Long orderId, Collection<PayopInvoiceEntity.Status> statuses);

    List<PayopInvoiceEntity> findByOrderIdOrderByCreatedAtDesc(Long orderId);

    /** Any attempt on the order still inside its 24 hours: manual claims wait for these. */
    boolean existsByOrderIdAndCreatedAtAfterAndStatusIn(Long orderId, Instant after,
                                                        Collection<PayopInvoiceEntity.Status> statuses);

    List<PayopInvoiceEntity> findByStatusInOrderByUpdatedAtDesc(Collection<PayopInvoiceEntity.Status> statuses,
                                                               Pageable page);

    List<PayopInvoiceEntity> findAllByOrderByCreatedAtDesc(Pageable page);
}
