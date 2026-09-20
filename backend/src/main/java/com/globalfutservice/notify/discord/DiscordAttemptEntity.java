package com.globalfutservice.notify.discord;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One {@code /verify} attempt, successful or not. See V28.
 *
 * <p>The in-memory rate limiter smooths bursts and forgets everything on restart. This is
 * the half that survives: somebody working through candidate order references leaves a
 * row per try, and "who got into this ticket, and when" has an answer afterwards.
 */
@Entity
@Table(name = "discord_verification_attempt")
public class DiscordAttemptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "discord_user_id", nullable = false, updatable = false)
    private String discordUserId;

    /** As typed. Not a foreign key — most of these will not be real. */
    @Column(name = "order_ref")
    private String orderRef;

    @Column(name = "outcome", nullable = false)
    private String outcome;

    @Column(name = "attempted_at", nullable = false, updatable = false)
    private Instant attemptedAt = Instant.now();

    protected DiscordAttemptEntity() {
    }

    public DiscordAttemptEntity(String discordUserId, String orderRef,
                                VerificationOutcome outcome) {
        this.discordUserId = discordUserId;
        this.orderRef = orderRef;
        this.outcome = outcome.name();
    }

    public Long getId() {
        return id;
    }

    public String getOutcome() {
        return outcome;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }
}
