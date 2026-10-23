package com.globalfutservice.loyalty;

import com.globalfutservice.domain.loyalty.PointsEntryType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;

public interface PointsLedgerRepository extends JpaRepository<PointsLedgerEntity, Long> {

    @Query("select coalesce(sum(p.amount), 0) from PointsLedgerEntity p where p.accountId = :accountId")
    long balanceOf(@Param("accountId") Long accountId);

    /**
     * The lifetime total behind the tier ladder: the entry types the caller names.
     *
     * <p>The set of types comes from {@code PointsEntryType.countsTowardLifetime()} rather than
     * being spelled out here, so a new entry type declares its own effect on tier standing in
     * one place instead of silently defaulting into -- or out of -- this query.
     *
     * <p>Less any line an admin has reversed: a corrected mistake leaves the tier as if it had
     * never been made. (The CORRECTION that reverses it never counts in the first place.)
     */
    @Query("select coalesce(sum(p.amount), 0) from PointsLedgerEntity p "
            + "where p.accountId = :accountId and p.entryType in :types "
            + "and not exists (select 1 from PointsLedgerEntity c where c.reversesId = p.id)")
    long lifetimeOf(@Param("accountId") Long accountId,
                    @Param("types") Collection<PointsEntryType> types);

    Optional<PointsLedgerEntity> findByOrderIdAndEntryType(Long orderId, PointsEntryType entryType);

    boolean existsByReversesId(Long reversesId);

    /** Holds the customer's account row until the transaction ends: one correction at a time. */
    @Query(value = "select id from account where id = :accountId for update", nativeQuery = true)
    Long lockAccount(@Param("accountId") Long accountId);

    Page<PointsLedgerEntity> findByAccountIdOrderByCreatedAtDesc(Long accountId, Pageable pageable);

    boolean existsByOrderIdAndEntryType(Long orderId, PointsEntryType entryType);

    boolean existsByAccountIdAndClaimDate(Long accountId, LocalDate claimDate);
}
