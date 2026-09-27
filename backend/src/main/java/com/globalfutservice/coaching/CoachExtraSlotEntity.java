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
 * One-off extra availability: a window a coach is working outside their weekly hours.
 * Slots inside it are generated exactly as they are inside the weekly hours.
 */
@Entity
@Table(name = "coach_extra_slot")
public class CoachExtraSlotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coach_id", nullable = false, updatable = false)
    private Long coachId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column
    private String reason;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected CoachExtraSlotEntity() {
    }

    public CoachExtraSlotEntity(Long coachId, Instant startsAt, Instant endsAt, String reason,
                                Long createdBy) {
        this.coachId = coachId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.reason = reason;
        this.createdBy = createdBy;
    }

    public TimeRange toRange() {
        return new TimeRange(startsAt, endsAt);
    }

    public Long getId() { return id; }
    public Long getCoachId() { return coachId; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public String getReason() { return reason; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
