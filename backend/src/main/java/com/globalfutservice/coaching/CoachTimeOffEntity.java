package com.globalfutservice.coaching;

import com.globalfutservice.domain.coaching.TimeRange;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A one-off absence.
 *
 * <p>Absolute instants, unlike {@link CoachAvailabilityEntity}: "I am away from the 3rd to
 * the 7th" is a statement about real elapsed time, not about a wall clock that repeats.
 */
@Entity
@Table(name = "coach_time_off")
public class CoachTimeOffEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coach_id", nullable = false)
    private Long coachId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected CoachTimeOffEntity() {
    }

    public CoachTimeOffEntity(Long coachId, Instant startsAt, Instant endsAt, String reason) {
        this.coachId = coachId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.reason = reason;
    }

    public TimeRange toRange() {
        return new TimeRange(startsAt, endsAt);
    }

    public Long getId() {
        return id;
    }

    public Long getCoachId() {
        return coachId;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public String getReason() {
        return reason;
    }
}
