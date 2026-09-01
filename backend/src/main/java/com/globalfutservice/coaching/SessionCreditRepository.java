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

    /** The soonest expiry still ahead, for the "use them by" line on the account page. */
    @Query("select min(c.expiresAt) from SessionCreditEntity c "
            + "where c.accountId = :accountId and c.expiresAt is not null "
            + "and c.entryType = com.globalfutservice.domain.coaching.SessionCreditType.GRANTED "
            + "and c.expiresAt > :now")
    Instant nextExpiryFor(@Param("accountId") Long accountId, @Param("now") Instant now);
}
