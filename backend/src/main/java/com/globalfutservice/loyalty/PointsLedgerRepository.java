package com.globalfutservice.loyalty;

import com.globalfutservice.domain.loyalty.PointsEntryType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;

public interface PointsLedgerRepository extends JpaRepository<PointsLedgerEntity, Long> {

    @Query("select coalesce(sum(p.amount), 0) from PointsLedgerEntity p where p.accountId = :accountId")
    long balanceOf(@Param("accountId") Long accountId);

    @Query("select coalesce(sum(p.amount), 0) from PointsLedgerEntity p "
            + "where p.accountId = :accountId and p.amount > 0")
    long lifetimeEarned(@Param("accountId") Long accountId);

    /**
     * Sum restricted to entry types the caller names.
     *
     * <p>Used for the lifetime total behind the tier ladder. The set of types comes from
     * {@code PointsEntryType.countsTowardLifetime()} rather than being spelled out here, so
     * a new entry type declares its own effect on tier standing in one place instead of
     * silently defaulting into — or out of — this query.
     */
    @Query("select coalesce(sum(p.amount), 0) from PointsLedgerEntity p "
            + "where p.accountId = :accountId and p.entryType in :types")
    long sumByEntryTypes(@Param("accountId") Long accountId,
                         @Param("types") Collection<PointsEntryType> types);

    Page<PointsLedgerEntity> findByAccountIdOrderByCreatedAtDesc(Long accountId, Pageable pageable);

    boolean existsByOrderIdAndEntryType(Long orderId, PointsEntryType entryType);

    boolean existsByAccountIdAndClaimDate(Long accountId, LocalDate claimDate);
}
