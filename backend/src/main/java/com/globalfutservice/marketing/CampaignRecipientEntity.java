package com.globalfutservice.marketing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One person, one campaign — the send log row.
 *
 * <p>Written before any mail goes out, so a crashed send resumes rather than restarting,
 * and a retry cannot double-send because the row is already SENT.
 */
@Entity
@Table(name = "email_campaign_recipient")
public class CampaignRecipientEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private Long campaignId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    /** Snapshotted at build time; the log must survive the customer changing address. */
    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column
    private String error;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "clicked_at")
    private Instant clickedAt;

    @Column(nullable = false, updatable = false)
    private UUID token = UUID.randomUUID();

    protected CampaignRecipientEntity() {
    }

    public CampaignRecipientEntity(Long campaignId, Long accountId, String email) {
        this.campaignId = campaignId;
        this.accountId = accountId;
        this.email = email;
    }

    public void markSent() {
        this.status = "SENT";
        this.sentAt = Instant.now();
        this.error = null;
    }

    /** Truncated: a stack-trace-length message in this column makes the table unreadable. */
    public void markFailed(String reason) {
        this.status = "FAILED";
        this.failedAt = Instant.now();
        this.error = reason == null ? null : reason.substring(0, Math.min(reason.length(), 500));
    }

    /**
     * Listed, then deliberately not sent.
     *
     * <p>Distinct from FAILED: nothing went wrong. The usual cause is somebody
     * unsubscribing between the list being built and their turn coming round, and
     * counting that as a failure would make a working unsubscribe look like a broken
     * mailer in the analytics.
     */
    public void markSkipped(String reason) {
        this.status = "SKIPPED";
        this.error = reason == null ? null : reason.substring(0, Math.min(reason.length(), 500));
    }

    /** First observation only — clients pre-fetch, and the second prefetch is not news. */
    public void markOpened() {
        if (this.openedAt == null) {
            this.openedAt = Instant.now();
        }
    }

    public void markClicked() {
        if (this.clickedAt == null) {
            this.clickedAt = Instant.now();
        }
        // A click is proof the mail was seen, whether or not the pixel ever loaded.
        markOpened();
    }

    public Long getId() { return id; }
    public Long getCampaignId() { return campaignId; }
    public Long getAccountId() { return accountId; }
    public String getEmail() { return email; }
    public String getStatus() { return status; }
    public Instant getSentAt() { return sentAt; }
    public Instant getFailedAt() { return failedAt; }
    public String getError() { return error; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getClickedAt() { return clickedAt; }
    public UUID getToken() { return token; }
}
