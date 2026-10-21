package com.globalfutservice.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationTokenEntity, Long> {

    Optional<EmailVerificationTokenEntity> findByTokenHash(String tokenHash);

    /**
     * Spends a link: 1 when this call used it, 0 when it was already used, voided or out of
     * time. One statement, so two clicks racing each other cannot both get a 1.
     */
    @Modifying
    @Query("update EmailVerificationTokenEntity t set t.usedAt = :now where t.id = :id "
            + "and t.usedAt is null and t.revokedAt is null and t.expiresAt > :now")
    int spend(@Param("id") Long id, @Param("now") Instant now);

    /** Voids every link still open for the account: a newer one was sent, or it is verified. */
    @Modifying
    @Query("update EmailVerificationTokenEntity t set t.revokedAt = :now where t.accountId = :accountId "
            + "and t.usedAt is null and t.revokedAt is null")
    int revokeOpen(@Param("accountId") Long accountId, @Param("now") Instant now);
}
