package com.globalfutservice.coaching;

import com.globalfutservice.domain.coaching.SessionCreditType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface SessionCreditRepository extends JpaRepository<SessionCreditEntity, Long> {

    /** The balance is the sum of entries. There is no balance column, deliberately. */
    @Query("select coalesce(sum(c.amount), 0) from SessionCreditEntity c "
            + "where c.accountId = :accountId")
    int balanceOf(@Param("accountId") Long accountId);

    boolean existsByOrderIdAndEntryType(Long orderId, SessionCreditType entryType);

    boolean existsBySessionIdAndEntryType(Long sessionId, SessionCreditType entryType);

    List<SessionCreditEntity> findByAccountIdOrderByCreatedAtDesc(Long accountId);

    /**
     * GRANTED rows oldest first, for working out which batch the next booking spends.
     *
     * <p>Credits are consumed in the order they were bought. That is the fair reading
     * when a customer holds both a block and a single session: the older purchase is the
     * one closer to expiring, and spending it first is what stops it lapsing unused.
     */
    @Query("select c from SessionCreditEntity c "
            + "where c.accountId = :accountId "
            + "and c.entryType = com.globalfutservice.domain.coaching.SessionCreditType.GRANTED "
            + "order by c.createdAt asc, c.id asc")
    List<SessionCreditEntity> grantsOldestFirst(@Param("accountId") Long accountId);

    /**
     * Sessions already taken out of the pool, net of any handed back.
     *
     * <p>CONSUMED is negative and RETURNED positive, so negating the sum gives a count
     * that goes back down when a session is cancelled in time -- which it must, or a
     * customer who cancelled would find their next booking billed against the following
     * batch and running for a different length than the one they bought.
     */
    @Query("select coalesce(-sum(c.amount), 0) from SessionCreditEntity c "
            + "where c.accountId = :accountId "
            + "and c.entryType in ("
            + "  com.globalfutservice.domain.coaching.SessionCreditType.CONSUMED, "
            + "  com.globalfutservice.domain.coaching.SessionCreditType.RETURNED)")
    int netConsumedBy(@Param("accountId") Long accountId);

    /** The soonest expiry still ahead, for the "use them by" line on the account page. */
    @Query("select min(c.expiresAt) from SessionCreditEntity c "
            + "where c.accountId = :accountId and c.expiresAt is not null "
            + "and c.entryType = com.globalfutservice.domain.coaching.SessionCreditType.GRANTED "
            + "and c.expiresAt > :now")
    Instant nextExpiryFor(@Param("accountId") Long accountId, @Param("now") Instant now);
}
