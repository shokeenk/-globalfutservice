package com.globalfutservice.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, Long> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    /** Reuse detected: burn the whole family, not just the presented token. */
    @Modifying
    @Query("update RefreshTokenEntity t set t.revokedAt = :now "
            + "where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") String familyId, @Param("now") Instant now);

    @Modifying
    @Query("update RefreshTokenEntity t set t.revokedAt = :now "
            + "where t.accountId = :accountId and t.revokedAt is null")
    int revokeAllForAccount(@Param("accountId") Long accountId, @Param("now") Instant now);

    @Modifying
    @Query("delete from RefreshTokenEntity t where t.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
