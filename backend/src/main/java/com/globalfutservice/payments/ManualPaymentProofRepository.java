package com.globalfutservice.payments;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ManualPaymentProofRepository extends JpaRepository<ManualPaymentProofEntity, Long> {

    Optional<ManualPaymentProofEntity> findByClaimId(Long claimId);

    /**
     * Which of these claims have a screenshot, without loading any of them.
     *
     * <p>The operator queue needs one boolean per row. Fetching the entities to answer it
     * would pull every attached image out of the database to display none of them, so
     * this projects the id and nothing else.
     */
    @Query("select p.claimId from ManualPaymentProofEntity p where p.claimId in :claimIds")
    List<Long> claimIdsWithProof(List<Long> claimIds);

    /**
     * Deletes screenshots uploaded before {@code cutoff}, except on orders somebody is
     * still working a problem on.
     *
     * <p>One native statement rather than load-then-delete: every row it removes holds an
     * image, and loading them first would pull each one out of the database only to throw
     * it away.
     *
     * <p>DISPUTED and ON_HOLD are the two states that mean a person is looking at something
     * wrong with the order, which is exactly when a payment screenshot stops being clutter
     * and becomes evidence. Those rows stay until the order moves on, and the next sweep
     * after that removes them. Every other state -- including orders that stalled and were
     * never closed -- is governed by the clock alone.
     *
     * @return how many screenshots were deleted
     */
    @Modifying
    @Query(value = """
            DELETE FROM manual_payment_proof p
             USING manual_payment_claim c, orders o
             WHERE p.claim_id = c.id
               AND c.order_id = o.id
               AND p.uploaded_at < :cutoff
               AND o.status NOT IN ('DISPUTED', 'ON_HOLD')
            """, nativeQuery = true)
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
