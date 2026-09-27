package com.globalfutservice.support;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One message in a support ticket: from the customer, a staff reply, or a staff note.
 *
 * <p>A note is staff-only. The database refuses a note written by anyone else, and every
 * endpoint a customer can reach reads messages of kind MESSAGE and nothing else.
 */
@Entity
@Table(name = "support_message")
public class SupportMessageEntity {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String STAFF = "STAFF";
    public static final String MESSAGE = "MESSAGE";
    public static final String NOTE = "NOTE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id", nullable = false, updatable = false)
    private Long ticketId;

    @Column(nullable = false, updatable = false)
    private String author;

    @Column(nullable = false, updatable = false)
    private String kind;

    @Column(nullable = false, updatable = false)
    private String body;

    @Column(name = "author_account_id", updatable = false)
    private Long authorAccountId;

    @Column(name = "author_label", updatable = false)
    private String authorLabel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected SupportMessageEntity() {
    }

    public SupportMessageEntity(Long ticketId, String author, String kind, String body,
                                Long authorAccountId, String authorLabel) {
        this.ticketId = ticketId;
        this.author = author;
        this.kind = kind;
        this.body = body;
        this.authorAccountId = authorAccountId;
        this.authorLabel = authorLabel;
    }

    public Long getId() {
        return id;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public String getAuthor() {
        return author;
    }

    public String getKind() {
        return kind;
    }

    public String getBody() {
        return body;
    }

    public Long getAuthorAccountId() {
        return authorAccountId;
    }

    public String getAuthorLabel() {
        return authorLabel;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
