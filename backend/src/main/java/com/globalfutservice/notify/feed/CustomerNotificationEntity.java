package com.globalfutservice.notify.feed;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One thing that happened, as the customer it happened to will read it.
 *
 * <p>Written at the same moment the Discord alert and the email go out, from the same
 * events — this is a third audience for those events, not a second event system. What
 * differs is the words: an operator alert says "GFS-26-XXXX paid, ready to fulfil", and
 * the row here says "Payment confirmed", because the two readers need different things
 * from the same fact.
 *
 * <p><b>Frozen text, not a template.</b> The title and body are stored as they were
 * written. A feed that re-renders old notifications from current templates quietly
 * rewrites history the first time the wording changes.
 */
@Entity
@Table(name = "customer_notification")
public class CustomerNotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Column(nullable = false, updatable = false)
    private String kind;

    @Column(nullable = false, updatable = false)
    private String title;

    @Column(updatable = false)
    private String body;

    @Column(updatable = false)
    private String link;

    @Column(name = "order_ref", updatable = false)
    private String orderRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** When this customer opened it. Null until they do — the badge counts these. */
    @Column(name = "read_at")
    private Instant readAt;

    protected CustomerNotificationEntity() {
        // JPA
    }

    public CustomerNotificationEntity(Long accountId, NotificationKind kind, String title,
                                      String body, String link, String orderRef) {
        this.accountId = accountId;
        this.kind = kind.name();
        this.title = title;
        this.body = body;
        this.link = link;
        this.orderRef = orderRef;
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getKind() {
        return kind;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getLink() {
        return link;
    }

    public String getOrderRef() {
        return orderRef;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void markRead(Instant at) {
        if (readAt == null) {
            readAt = at;
        }
    }
}
