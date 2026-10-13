package com.globalfutservice.catalog.listing;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One uploaded PNG for a listing's card (V49). Never changed once written: replacing the
 * artwork writes another row, so an image id always means the same bytes and can be
 * cached for ever, and an order's snapshot keeps showing the artwork it was bought with.
 *
 * <p>Kept in the database because the host's disk is wiped on every deploy.
 */
@Entity
@Table(name = "service_listing_image")
public class ServiceListingImageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "listing_id", nullable = false)
    private Long listingId;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    /** Plain byte[] to BYTEA, as manual_payment_proof does: never @Lob (BinaryColumnMappingTest). */
    @Basic(fetch = FetchType.LAZY)
    @Column(nullable = false)
    private byte[] bytes;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    protected ServiceListingImageEntity() {
    }

    public ServiceListingImageEntity(Long listingId, byte[] bytes, int width, int height, Long uploadedBy, Instant at) {
        this.listingId = listingId;
        this.contentType = "image/png";
        this.bytes = bytes;
        this.sizeBytes = bytes.length;
        this.width = width;
        this.height = height;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = at;
    }

    public Long getId() {
        return id;
    }

    public Long getListingId() {
        return listingId;
    }

    public String getContentType() {
        return contentType;
    }

    public int getSizeBytes() {
        return sizeBytes;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public byte[] getBytes() {
        return bytes;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
