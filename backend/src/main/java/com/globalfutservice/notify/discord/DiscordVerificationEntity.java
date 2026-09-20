package com.globalfutservice.notify.discord;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One order, claimed by one Discord account. See V28.
 *
 * <p>The row exists so a second person who has seen the same order reference cannot take
 * the ticket. Written once and never updated: moving a ticket to a different account is
 * an operator deleting this row, not the customer re-running a command.
 */
@Entity
@Table(name = "discord_order_verification")
public class DiscordVerificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    /** The snowflake. Usernames change; this does not. */
    @Column(name = "discord_user_id", nullable = false, updatable = false)
    private String discordUserId;

    /** What they were called at the time. For a human reading the row, never matched on. */
    @Column(name = "discord_username")
    private String discordUsername;

    @Column(name = "channel_id")
    private String channelId;

    @Column(name = "verified_at", nullable = false, updatable = false)
    private Instant verifiedAt = Instant.now();

    protected DiscordVerificationEntity() {
    }

    public DiscordVerificationEntity(Long orderId, String discordUserId,
                                     String discordUsername, String channelId) {
        this.orderId = orderId;
        this.discordUserId = discordUserId;
        this.discordUsername = discordUsername;
        this.channelId = channelId;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getDiscordUserId() {
        return discordUserId;
    }

    public String getDiscordUsername() {
        return discordUsername;
    }

    public String getChannelId() {
        return channelId;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    /** True when this order already belongs to somebody other than the caller. */
    public boolean claimedBySomeoneOtherThan(String candidateUserId) {
        return !discordUserId.equals(candidateUserId);
    }
}
