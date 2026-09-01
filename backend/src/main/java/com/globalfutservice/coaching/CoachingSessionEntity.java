package com.globalfutservice.coaching;

import com.globalfutservice.domain.coaching.SessionStatus;
import com.globalfutservice.domain.coaching.TimeRange;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * One booked slot.
 *
 * <p>Note there is no {@code setStatus}. Every status change goes through
 * {@code CoachingService.transition}, which validates against
 * {@code SessionStateMachine}, settles the credit and writes an event row — all in one
 * transaction. That is the same discipline {@code OrderEntity} follows, and for the same
 * reason: it is what makes "every change is legal and every change is audited" a statement
 * about the system rather than a hope about its callers.
 */
@Entity
@Table(name = "coaching_session")
public class CoachingSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_ref", nullable = false, updatable = false)
    private String publicRef;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "coach_id", nullable = false)
    private Long coachId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    /**
     * The zone the customer booked in.
     *
     * <p>Kept so a reminder can say "19:00 your time" rather than the coach's time or the
     * server's. Someone who books at 19:00 IST and reads "13:30" in an email assumes we
     * moved their session.
     */
    @Column(name = "customer_timezone")
    private String customerTimezone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionStatus status = SessionStatus.SCHEDULED;

    /**
     * Whether the credit came back. Stored rather than derived from the status.
     *
     * <p>For a customer cancellation the answer depends on how much notice they gave, and
     * re-deriving it later against a policy that has since changed would silently rewrite
     * what the customer was told when they cancelled.
     */
    @Column(name = "credit_returned", nullable = false)
    private boolean creditReturned = false;

    @Column(name = "reschedule_count", nullable = false)
    private int rescheduleCount = 0;

    @Column(name = "meeting_url")
    private String meetingUrl;

    @Column(name = "customer_note")
    private String customerNote;

    @Column(name = "coach_note")
    private String coachNote;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** Optimistic locking, so two operators acting on one session cannot both win. */
    @Version
    private long version;

    protected CoachingSessionEntity() {
    }

    public CoachingSessionEntity(String publicRef, Long accountId, Long coachId, Long orderId,
                                 Instant startsAt, Instant endsAt, String customerTimezone) {
        this.publicRef = publicRef;
        this.accountId = accountId;
        this.coachId = coachId;
        this.orderId = orderId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.customerTimezone = customerTimezone;
    }

    public TimeRange toRange() {
        return new TimeRange(startsAt, endsAt);
    }

    public boolean isUpcoming(Instant now) {
        return status == SessionStatus.SCHEDULED && startsAt.isAfter(now);
    }

    // ---- mutation, deliberately narrow -----------------------------------------------

    /** Package-private: only {@code CoachingService} settles a session. */
    void applyTransition(SessionStatus to, boolean creditReturned, Instant at) {
        this.status = to;
        this.creditReturned = creditReturned;
        this.settledAt = at;
        this.updatedAt = at;
    }

    /** Package-private: only {@code CoachingService} moves a session. */
    void applyReschedule(Instant newStart, Instant newEnd, String timezone, Instant at) {
        this.startsAt = newStart;
        this.endsAt = newEnd;
        if (timezone != null) {
            this.customerTimezone = timezone;
        }
        this.rescheduleCount += 1;
        this.updatedAt = at;
    }

    public void setMeetingUrl(String meetingUrl) {
        this.meetingUrl = meetingUrl;
        this.updatedAt = Instant.now();
    }

    public void setCoachNote(String coachNote) {
        this.coachNote = coachNote;
        this.updatedAt = Instant.now();
    }

    public void setCustomerNote(String customerNote) {
        this.customerNote = customerNote;
    }

    // ---- accessors --------------------------------------------------------------------

    public Long getId() {
        return id;
    }

    public String getPublicRef() {
        return publicRef;
    }

    public Long getAccountId() {
        return accountId;
    }

    public Long getCoachId() {
        return coachId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public String getCustomerTimezone() {
        return customerTimezone;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public boolean isCreditReturned() {
        return creditReturned;
    }

    public int getRescheduleCount() {
        return rescheduleCount;
    }

    public String getMeetingUrl() {
        return meetingUrl;
    }

    public String getCustomerNote() {
        return customerNote;
    }

    public String getCoachNote() {
        return coachNote;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
