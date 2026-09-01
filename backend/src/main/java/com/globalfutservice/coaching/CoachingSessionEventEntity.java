package com.globalfutservice.coaching;

import com.globalfutservice.domain.coaching.SessionActor;
import com.globalfutservice.domain.coaching.SessionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One thing that happened to a session.
 *
 * <p>Covers more than status changes: a reschedule keeps a session SCHEDULED and has no
 * transition to hang itself off, so it records the times it moved between instead. The
 * timeline is what answers a disputed session, and "the customer moved it twice and then
 * did not turn up" has to be visible without inference.
 */
@Entity
@Table(name = "coaching_session_event")
public class CoachingSessionEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private SessionStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status")
    private SessionStatus toStatus;

    @Column(name = "from_time")
    private Instant fromTime;

    @Column(name = "to_time")
    private Instant toTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionActor actor;

    @Column(name = "actor_id")
    private Long actorId;

    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected CoachingSessionEventEntity() {
    }

    public static CoachingSessionEventEntity booked(Long sessionId, SessionActor actor,
                                                    Long actorId, Instant at) {
        CoachingSessionEventEntity e = new CoachingSessionEventEntity();
        e.sessionId = sessionId;
        e.eventType = "BOOKED";
        e.toStatus = SessionStatus.SCHEDULED;
        e.toTime = at;
        e.actor = actor;
        e.actorId = actorId;
        return e;
    }

    public static CoachingSessionEventEntity transitioned(Long sessionId, SessionStatus from,
                                                          SessionStatus to, SessionActor actor,
                                                          Long actorId, String detail) {
        CoachingSessionEventEntity e = new CoachingSessionEventEntity();
        e.sessionId = sessionId;
        e.eventType = "STATUS";
        e.fromStatus = from;
        e.toStatus = to;
        e.actor = actor;
        e.actorId = actorId;
        e.detail = detail;
        return e;
    }

    public static CoachingSessionEventEntity rescheduled(Long sessionId, Instant from, Instant to,
                                                         SessionActor actor, Long actorId) {
        CoachingSessionEventEntity e = new CoachingSessionEventEntity();
        e.sessionId = sessionId;
        e.eventType = "RESCHEDULED";
        e.fromStatus = SessionStatus.SCHEDULED;
        e.toStatus = SessionStatus.SCHEDULED;
        e.fromTime = from;
        e.toTime = to;
        e.actor = actor;
        e.actorId = actorId;
        return e;
    }

    public Long getId() {
        return id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public String getEventType() {
        return eventType;
    }

    public SessionStatus getFromStatus() {
        return fromStatus;
    }

    public SessionStatus getToStatus() {
        return toStatus;
    }

    public Instant getFromTime() {
        return fromTime;
    }

    public Instant getToTime() {
        return toTime;
    }

    public SessionActor getActor() {
        return actor;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
