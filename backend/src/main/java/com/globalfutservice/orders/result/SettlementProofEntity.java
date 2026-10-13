package com.globalfutservice.orders.result;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The screenshot an admin attached when settling a result (V47). One per result; a new
 * upload replaces it. Staff's alone, like a payment screenshot.
 */
@Entity
@Table(name = "settlement_proof")
public class SettlementProofEntity {

    @Id
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    /** Plain byte[] to BYTEA, as manual_payment_proof does: never @Lob (BinaryColumnMappingTest). */
    @Basic(fetch = FetchType.LAZY)
    @Column(nullable = false)
    private byte[] bytes;

    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    protected SettlementProofEntity() {
    }

    public SettlementProofEntity(Long orderId, String contentType, byte[] bytes, Long uploadedBy, Instant at) {
        this.orderId = orderId;
        replaceWith(contentType, bytes, uploadedBy, at);
    }

    public void replaceWith(String contentType, byte[] bytes, Long uploadedBy, Instant at) {
        this.contentType = contentType;
        this.bytes = bytes;
        this.sizeBytes = bytes.length;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = at;
    }

    public Long getOrderId() {
        return orderId;
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

    public Long getUploadedBy() {
        return uploadedBy;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
