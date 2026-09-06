package com.globalfutservice.payments;

import com.globalfutservice.domain.payments.ClaimStatus;
import com.globalfutservice.domain.payments.ManualPaymentMethod;
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
 * A customer's assertion that they sent money outside the gateway.
 *
 * <p>Everything on this row up to {@link #status} is customer-supplied and unverified.
 * Nothing reads it as proof of payment; the only thing that releases an order is an
 * operator calling {@code verify}.
 */
@Entity
@Table(name = "manual_payment_claim")
public class ManualPaymentClaimEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ManualPaymentMethod method;

    /**
     * The address the customer was shown, captured at claim time. See V18 -- this is what
     * makes a claim reconcilable after a handle or wallet changes.
     */
    @Column(nullable = false)
    private String destination;

    /** As typed, trimmed only. Never parsed into a shape. */
    @Column(nullable = false)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClaimStatus status = ClaimStatus.SUBMITTED;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt = Instant.now();

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "review_note")
    private String reviewNote;

    protected ManualPaymentClaimEntity() {
        // JPA
    }

    public ManualPaymentClaimEntity(Long orderId, ManualPaymentMethod method,
                                    String destination, String reference) {
        this.orderId = orderId;
        this.method = method;
        this.destination = destination;
        this.reference = reference;
    }

    /**
     * Records an operator's decision. Both outcomes go through here so that the
     * reviewer and the timestamp can never be set for one and forgotten for the other --
     * the database check constraint in V18 enforces the same pairing.
     */
    public void review(ClaimStatus outcome, Long reviewerAccountId, String note) {
        if (!outcome.isReviewed()) {
            throw new IllegalArgumentException("A review outcome must be VERIFIED or REJECTED.");
        }
        this.status = outcome;
        this.reviewedBy = reviewerAccountId;
        this.reviewedAt = Instant.now();
        this.reviewNote = note;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public ManualPaymentMethod getMethod() {
        return method;
    }

    public String getDestination() {
        return destination;
    }

    public String getReference() {
        return reference;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public Long getReviewedBy() {
        return reviewedBy;
    }

    public String getReviewNote() {
        return reviewNote;
    }
}
