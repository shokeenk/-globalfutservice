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

    @Query("select count(c) from CredentialVaultEntity c where c.purgedAt is null")
    long countHeld();
}
