package com.globalfutservice.support;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A support request.
 *
 * <p>Kept in the database rather than emailed to an inbox, so that a customer's history
 * is attached to their orders and a chargeback representment can show what was asked and
 * when it was answered.
 */
@Entity
@Table(name = "support_ticket")
public class SupportTicketEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_ref", nullable = false, updatable = false)
    private String publicRef;

    @Column(name = "account_id")
    private Long accountId;

    /** Optional link to an order, by its public reference. */
    @Column(name = "order_ref")
    private String orderRef;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false, length = 4000)
    private String body;

    @Column(nullable = false)
    private String status = "OPEN";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected SupportTicketEntity() {
    }

    public SupportTicketEntity(String publicRef, Long accountId, String orderRef,
                               String email, String subject, String body) {
        this.publicRef = publicRef;
        this.accountId = accountId;
        this.orderRef = orderRef;
        this.email = email;
        this.subject = subject;
        this.body = body;
    }

    public Long getId() {
        return id;
    }

    public String getPublicRef() {
        return publicRef;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getOrderRef() {
        return orderRef;
    }

    public String getEmail() {
        return email;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
