package com.globalfutservice.payments;

import com.globalfutservice.domain.payments.ClaimStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ManualPaymentClaimRepository extends JpaRepository<ManualPaymentClaimEntity, Long> {

    Optional<ManualPaymentClaimEntity> findByOrderIdAndStatus(Long orderId, ClaimStatus status);

    /** Everything on one order, newest first: the current claim plus any rejected attempts. */
    List<ManualPaymentClaimEntity> findByOrderIdOrderBySubmittedAtDesc(Long orderId);

    /**
     * The review queue. Oldest first on purpose -- the customer who has been waiting
     * longest is the one whose money should be looked for next.
     */
    Page<ManualPaymentClaimEntity> findByStatusOrderBySubmittedAtAsc(ClaimStatus status, Pageable pageable);

    long countByStatus(ClaimStatus status);

    /**
     * Used by the operator holding a bank statement line. Returns a list rather than an
     * optional because two customers quoting the same reference is a real situation and
     * one an operator has to be shown, not shielded from.
     */
    List<ManualPaymentClaimEntity> findByReferenceOrderBySubmittedAtDesc(String reference);
}
