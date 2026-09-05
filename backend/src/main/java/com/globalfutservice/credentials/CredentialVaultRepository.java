package com.globalfutservice.credentials;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface CredentialVaultRepository extends JpaRepository<CredentialVaultEntity, Long> {

    @Query("""
            select c from CredentialVaultEntity c
            where c.purgedAt is null and c.purgeAfter < :now
            """)
    List<CredentialVaultEntity> findDueForPurge(@Param("now") Instant now);

    /**
     * Whether a sign-in is already sealed for this order and still un-purged.
     *
     * <p>Asked at payment time. Credentials may now arrive before the money does, so
     * "does this order already have what it needs" is what decides whether a paid order
     * queues for delivery or waits for a sign-in that has in fact already been given.
     */
    boolean existsByOrderIdAndPurgedAtIsNull(Long orderId);

    @Query("select count(c) from CredentialVaultEntity c where c.purgedAt is null")
    long countHeld();
}
