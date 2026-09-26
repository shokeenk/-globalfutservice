package com.globalfutservice.coaching;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface CoachExtraSlotRepository extends JpaRepository<CoachExtraSlotEntity, Long> {

    /** Extra windows that overlap a period, for slot generation. */
    @Query("select e from CoachExtraSlotEntity e where e.coachId = :coachId "
            + "and e.startsAt < :to and e.endsAt > :from order by e.startsAt")
    List<CoachExtraSlotEntity> overlapping(@Param("coachId") Long coachId,
                                           @Param("from") Instant from,
                                           @Param("to") Instant to);

    /** What the admin sees: every extra window that has not finished yet. */
    List<CoachExtraSlotEntity> findByCoachIdAndEndsAtAfterOrderByStartsAt(Long coachId, Instant now);
}
