package com.globalfutservice.coaching;

import com.globalfutservice.domain.coaching.AvailabilityRule;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;

/**
 * One recurring window in a coach's week, stored as the coach declared it: a weekday and
 * two local times. See {@link AvailabilityRule} for why this is not an instant.
 */
@Entity
@Table(name = "coach_availability")
public class CoachAvailabilityEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coach_id", nullable = false)
    private Long coachId;

    /** ISO-8601: Monday is 1, Sunday is 7 — the same numbering as {@link DayOfWeek}. */
    @Column(name = "day_of_week", nullable = false)
    private short dayOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected CoachAvailabilityEntity() {
    }

    public CoachAvailabilityEntity(Long coachId, DayOfWeek day, LocalTime start, LocalTime end) {
        this.coachId = coachId;
        this.dayOfWeek = (short) day.getValue();
        this.startTime = start;
        this.endTime = end;
    }

    /** Into the framework-free value the slot planner works with. */
    public AvailabilityRule toRule() {
        return new AvailabilityRule(DayOfWeek.of(dayOfWeek), startTime, endTime);
    }

    public Long getId() {
        return id;
    }

    public Long getCoachId() {
        return coachId;
    }

    public DayOfWeek getDay() {
        return DayOfWeek.of(dayOfWeek);
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }
}
