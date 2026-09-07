package com.globalfutservice.payments;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
