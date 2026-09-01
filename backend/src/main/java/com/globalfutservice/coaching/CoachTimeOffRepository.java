package com.globalfutservice.coaching;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface CoachTimeOffRepository extends JpaRepository<CoachTimeOffEntity, Long> {

    /**
     * Absences that touch the window at all.
     *
     * <p>Overlap, not containment: a fortnight away blocks a window sitting entirely
     * inside it, which a naive {@code startsAt between :from and :to} would miss — and the
     * failure mode is selling slots during a holiday.
     */
    @Query("select t from CoachTimeOffEntity t where t.coachId = :coachId "
            + "and t.startsAt < :to and t.endsAt > :from")
    List<CoachTimeOffEntity> overlapping(@Param("coachId") Long coachId,
                                         @Param("from") Instant from,
                                         @Param("to") Instant to);

    List<CoachTimeOffEntity> findByCoachIdOrderByStartsAtAsc(Long coachId);
}
