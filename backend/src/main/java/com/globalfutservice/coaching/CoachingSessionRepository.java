package com.globalfutservice.coaching;

import com.globalfutservice.domain.coaching.SessionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Note the shape of the customer-facing lookup: it takes the account id as part of the
 * query rather than fetching by reference and checking ownership afterwards. Same rule the
 * orders module follows — object-level authorisation belongs in the {@code where} clause,
 * because an {@code if} after the fetch is one early return away from being skipped.
 */
public interface CoachingSessionRepository extends JpaRepository<CoachingSessionEntity, Long> {

    Optional<CoachingSessionEntity> findByPublicRef(String publicRef);

    Optional<CoachingSessionEntity> findByPublicRefAndAccountId(String publicRef, Long accountId);

    Page<CoachingSessionEntity> findByAccountIdOrderByStartsAtDesc(Long accountId,
                                                                  Pageable pageable);

    List<CoachingSessionEntity> findByAccountIdAndStatusOrderByStartsAtAsc(Long accountId,
                                                                          SessionStatus status);

    /**
     * What already occupies a coach's calendar.
     *
     * <p>Only SCHEDULED sessions block. A cancelled one has released its slot, and treating
     * it as busy would slowly starve the calendar of exactly the evening times people most
     * want to book.
     */
    @Query("select s from CoachingSessionEntity s where s.coachId = :coachId "
            + "and s.status = com.globalfutservice.domain.coaching.SessionStatus.SCHEDULED "
            + "and s.startsAt < :to and s.endsAt > :from")
    List<CoachingSessionEntity> busyBetween(@Param("coachId") Long coachId,
                                            @Param("from") Instant from,
                                            @Param("to") Instant to);

    @Query("select s from CoachingSessionEntity s where s.coachId = :coachId "
            + "and s.startsAt >= :from and s.startsAt < :to order by s.startsAt")
    List<CoachingSessionEntity> forCoachBetween(@Param("coachId") Long coachId,
                                                @Param("from") Instant from,
                                                @Param("to") Instant to);

    /** Drives the no-show sweep: still SCHEDULED well after it should have happened. */
    List<CoachingSessionEntity> findByStatusAndStartsAtBefore(SessionStatus status, Instant cutoff);

    /** Drives the reminder job. */
    @Query("select s from CoachingSessionEntity s "
            + "where s.status = com.globalfutservice.domain.coaching.SessionStatus.SCHEDULED "
            + "and s.startsAt >= :from and s.startsAt < :to")
    List<CoachingSessionEntity> scheduledBetween(@Param("from") Instant from,
                                                 @Param("to") Instant to);
}
