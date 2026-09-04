package com.globalfutservice.coaching;

import com.globalfutservice.domain.coaching.SessionCreditType;
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
 * One movement in a customer's session-credit balance. Append-only, like the points
 * ledger: the balance is the sum of these rows and there is no balance column anywhere.
 */
@Entity
@Table(name = "session_credit")
public class SessionCreditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "session_id")
    private Long sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private SessionCreditType entryType;

    /** Signed. The database enforces that the sign matches the entry type. */
    @Column(nullable = false)
    private int amount;

    /** Set on GRANTED rows only — when this batch of credits stops being usable. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /**
     * How long a session bought by this credit runs, in minutes. GRANTED rows only.
     *
     * <p>Stamped at grant time rather than read from configuration at booking time, so
     * changing either length later cannot retroactively shorten a session somebody has
     * already paid for.
     */
    @Column(name = "session_minutes")
    private Integer sessionMinutes;

    private String description;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected SessionCreditEntity() {
    }

    private SessionCreditEntity(Long accountId, Long orderId, Long sessionId,
                                SessionCreditType entryType, int amount, Instant expiresAt,
                                String description, Long actorId) {
        this.accountId = accountId;
        this.orderId = orderId;
        this.sessionId = sessionId;
        this.entryType = entryType;
        this.amount = amount;
        this.expiresAt = expiresAt;
        this.description = description;
        this.actorId = actorId;
    }

    public static SessionCreditEntity granted(Long accountId, Long orderId, int credits,
                                              Instant expiresAt, String orderRef,
                                              int sessionMinutes) {
        SessionCreditEntity entry = new SessionCreditEntity(accountId, orderId, null,
                SessionCreditType.GRANTED, credits, expiresAt,
                credits + " session credits from order " + orderRef, null);
        entry.sessionMinutes = sessionMinutes;
        return entry;
    }

    public static SessionCreditEntity consumed(Long accountId, Long sessionId, String sessionRef) {
        return new SessionCreditEntity(accountId, null, sessionId, SessionCreditType.CONSUMED,
                -1, null, "Booked session " + sessionRef, null);
    }

    public static SessionCreditEntity returned(Long accountId, Long sessionId, String sessionRef,
                                               String why) {
        return new SessionCreditEntity(accountId, null, sessionId, SessionCreditType.RETURNED,
                1, null, "Returned from " + sessionRef + " (" + why + ")", null);
    }

    public static SessionCreditEntity expired(Long accountId, int credits, String description) {
        return new SessionCreditEntity(accountId, null, null, SessionCreditType.EXPIRED,
                -Math.abs(credits), null, description, null);
    }

    public static SessionCreditEntity adjustment(Long accountId, int signedAmount,
                                                 String description, Long actorId) {
        return new SessionCreditEntity(accountId, null, null, SessionCreditType.MANUAL_ADJUSTMENT,
                signedAmount, null, description, actorId);
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public SessionCreditType getEntryType() {
        return entryType;
    }

    public int getAmount() {
        return amount;
    }

    /** Minutes a session bought by this credit runs, or null on non-GRANTED rows. */
    public Integer getSessionMinutes() {
        return sessionMinutes;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
