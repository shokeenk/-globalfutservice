package com.globalfutservice.payments;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A customer's screenshot of the payment they say they made.
 *
 * <p>Evidence, never authorisation. An operator still has to find the money in the
 * account; this only makes the difference between a mistyped reference and a payment
 * that has not arrived visible without a support thread.
 */
@Entity
@Table(name = "manual_payment_proof")
public class ManualPaymentProofEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false, updatable = false)
    private Long claimId;

    /** Decided by the server from the file's magic bytes, never taken from the request. */
    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    /**
     * Lazy, and that is load-bearing rather than a micro-optimisation.
     *
     * <p>The operator queue lists claims and asks only whether a screenshot exists. Left
     * eager, every refresh of that list would pull every attached image out of the
     * database to display none of them.
     */
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(nullable = false)
    private byte[] bytes;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt = Instant.now();

    protected ManualPaymentProofEntity() {
        // JPA
    }

    public ManualPaymentProofEntity(Long claimId, String contentType, byte[] bytes) {
        this.claimId = claimId;
        this.contentType = contentType;
        this.bytes = bytes;
        this.sizeBytes = bytes.length;
    }

    /**
     * Replaces the stored image in place.
     *
     * <p>Used when a customer uploads a clearer screenshot. Overwriting rather than
     * inserting keeps the one-proof-per-claim rule the unique index enforces, and means
     * an operator is never choosing between two images without knowing which is current.
     */
    public void replaceWith(String newContentType, byte[] newBytes) {
        this.contentType = newContentType;
        this.bytes = newBytes;
        this.sizeBytes = newBytes.length;
        this.uploadedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public String getContentType() {
        return contentType;
    }

    public int getSizeBytes() {
        return sizeBytes;
    }

    public byte[] getBytes() {
        return bytes;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
