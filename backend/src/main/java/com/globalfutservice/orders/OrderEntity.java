package com.globalfutservice.orders;

import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.orders.DeliveryMethod;
import com.globalfutservice.domain.orders.OrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An order.
 *
 * <p>The important column here is {@code priceBreakdown}: the entire accepted quote,
 * frozen as JSON at creation. When the client raises rates next month, this order's
 * invoice, its refund amount and its dispute evidence must all still show what was
 * actually agreed. Recomputing a historical price from a current rate card is the most
 * common serious bug in storefronts of this shape, and a snapshot column removes the
 * possibility entirely.
 *
 * <p>{@code quoteId} is unique, which is what makes a stateless signed quote safe: one
 * quote can become at most one order, so a replayed request is rejected by the database
 * rather than by hopeful application logic.
 */
@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_ref", nullable = false, updatable = false)
    private String publicRef;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "guest_email")
    private String guestEmail;

    @Column(name = "guest_phone")
    private String guestPhone;

    @Column(nullable = false)
    private String season;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Sku sku;

    @Enumerated(EnumType.STRING)
    private Platform platform;

    private String variant;

    /** The human name this was sold under, taken from the quote's BASE line. */
    @Column(name = "service_label")
    private String serviceLabel;

    /** The coupon applied, frozen. Kept on the order so the queue needs no join. */
    @Column(name = "coupon_code")
    private String couponCode;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_method", nullable = false)
    private DeliveryMethod deliveryMethod;

    @Enumerated(EnumType.STRING)
    // The column is CHAR(3), not VARCHAR — a currency code is exactly three characters
    // and V1__baseline.sql says so. Hibernate assumes varchar(255) for a string enum, so
    // without this the schema validator rejects the real column and startup fails.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(name = "subtotal_minor", nullable = false)
    private long subtotalMinor;

    @Column(name = "total_minor", nullable = false)
    private long totalMinor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "price_breakdown", nullable = false, columnDefinition = "jsonb")
    private String priceBreakdown;

    @Column(name = "points_redeemed", nullable = false)
    private long pointsRedeemed;

    @Column(name = "points_earned", nullable = false)
    private long pointsEarned;

    @Column(name = "points_settled_at")
    private Instant pointsSettledAt;

    @Column(name = "referral_code")
    private String referralCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status = OrderStatus.DRAFT;

    @Column(name = "quote_id", nullable = false, updatable = false)
    private String quoteId;

    /** EA account name / gamertag. Not a credential — just how the trader finds them. */
    @Column(name = "ea_platform_handle")
    private String eaPlatformHandle;

    /* ---------------------------------------------------- supplier fulfilment --- */

    /**
     * The FUT Transfer side of this order.
     *
     * <p>Null until credentials are dispatched, and permanently null for coaching. The
     * supplier's own status vocabularies are kept verbatim rather than only as our mapped
     * {@link OrderStatus}: {@code accountCheck} and {@code economyState} name the specific
     * thing a customer must fix, and that is the half that makes a hold actionable.
     */
    @Column(name = "supplier_order_id")
    private String supplierOrderId;

    @Column(name = "supplier_status")
    private String supplierStatus;

    @Column(name = "supplier_account_check")
    private String supplierAccountCheck;

    @Column(name = "supplier_economy_state")
    private String supplierEconomyState;

    @Column(name = "supplier_amount_ordered")
    private Long supplierAmountOrdered;

    @Column(name = "supplier_amount_delivered")
    private Long supplierAmountDelivered;

    /** Last <em>successful</em> read, kept apart from updatedAt so a stalled poller shows. */
    @Column(name = "supplier_polled_at")
    private Instant supplierPolledAt;

    @Column(name = "supplier_dispatch_attempts", nullable = false)
    private int supplierDispatchAttempts;

    @Column(name = "customer_note")
    private String customerNote;

    @Column(name = "operator_note")
    private String operatorNote;

    /** Set once, never nulled. The contractual moment fulfilment completed. */
    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "guarantee_expires_at")
    private Instant guaranteeExpiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * Optimistic locking. Two operators opening the same order in the queue and both
     * clicking "Delivered" would otherwise both succeed, sending two emails and starting
     * two guarantee clocks. The second write now fails and the UI re-reads.
     */
    @Version
    private Long version;

    protected OrderEntity() {
    }

    public OrderEntity(String publicRef, String quoteId, String season, Sku sku, Platform platform,
                       String variant, BigDecimal quantity, DeliveryMethod deliveryMethod,
                       Currency currency, long subtotalMinor, long totalMinor, String priceBreakdown) {
        this.publicRef = publicRef;
        this.quoteId = quoteId;
        this.season = season;
        this.sku = sku;
        this.platform = platform;
        this.variant = variant;
        this.quantity = quantity;
        this.deliveryMethod = deliveryMethod;
        this.currency = currency;
        this.subtotalMinor = subtotalMinor;
        this.totalMinor = totalMinor;
        this.priceBreakdown = priceBreakdown;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public Money total() {
        return Money.ofMinor(totalMinor, currency);
    }

    public boolean isGuest() {
        return accountId == null;
    }

    public String contactEmail() {
        return guestEmail;
    }

    public boolean requiresCredentials() {
        return deliveryMethod.requiresCredentials();
    }

    // --- accessors ------------------------------------------------------------

    public Long getId() {
        return id;
    }

    public String getPublicRef() {
        return publicRef;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getGuestEmail() {
        return guestEmail;
    }

    public void setGuestEmail(String guestEmail) {
        this.guestEmail = guestEmail;
    }

    public String getGuestPhone() {
        return guestPhone;
    }

    public void setGuestPhone(String guestPhone) {
        this.guestPhone = guestPhone;
    }

    public String getSeason() {
        return season;
    }

    public Sku getSku() {
        return sku;
    }

    public Platform getPlatform() {
        return platform;
    }

    /**
     * What this order was sold as, frozen at purchase.
     *
     * <p>Set once from the quote's BASE line and never recomputed. A live lookup against
     * the rate card would rename historical orders every time the catalogue is retitled;
     * this keeps an order's name the one the customer agreed to.
     */
    public String getServiceLabel() {
        return serviceLabel;
    }

    public void setServiceLabel(String serviceLabel) {
        this.serviceLabel = serviceLabel;
    }

    public String getCouponCode() {
        return couponCode;
    }

    public void setCouponCode(String couponCode) {
        this.couponCode = couponCode;
    }

    public String getVariant() {
        return variant;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public DeliveryMethod getDeliveryMethod() {
        return deliveryMethod;
    }

    public void setDeliveryMethod(DeliveryMethod deliveryMethod) {
        this.deliveryMethod = deliveryMethod;
    }

    public Currency getCurrency() {
        return currency;
    }

    public long getSubtotalMinor() {
        return subtotalMinor;
    }

    public long getTotalMinor() {
        return totalMinor;
    }

    public String getPriceBreakdown() {
        return priceBreakdown;
    }

    public long getPointsRedeemed() {
        return pointsRedeemed;
    }

    public void setPointsRedeemed(long pointsRedeemed) {
        this.pointsRedeemed = pointsRedeemed;
    }

    public long getPointsEarned() {
        return pointsEarned;
    }

    public void setPointsEarned(long pointsEarned) {
        this.pointsEarned = pointsEarned;
    }

    public Instant getPointsSettledAt() {
        return pointsSettledAt;
    }

    public void setPointsSettledAt(Instant pointsSettledAt) {
        this.pointsSettledAt = pointsSettledAt;
    }

    public String getReferralCode() {
        return referralCode;
    }

    public void setReferralCode(String referralCode) {
        this.referralCode = referralCode;
    }

    public OrderStatus getStatus() {
        return status;
    }

    void setStatus(OrderStatus status) {
        this.status = status;
    }

    public String getQuoteId() {
        return quoteId;
    }

    public String getEaPlatformHandle() {
        return eaPlatformHandle;
    }

    public void setEaPlatformHandle(String eaPlatformHandle) {
        this.eaPlatformHandle = eaPlatformHandle;
    }

    public String getCustomerNote() {
        return customerNote;
    }

    public void setCustomerNote(String customerNote) {
        this.customerNote = customerNote;
    }

    public String getOperatorNote() {
        return operatorNote;
    }

    public void setOperatorNote(String operatorNote) {
        this.operatorNote = operatorNote;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    void setDeliveredAt(Instant deliveredAt) {
        this.deliveredAt = deliveredAt;
    }

    public Instant getGuaranteeExpiresAt() {
        return guaranteeExpiresAt;
    }

    void setGuaranteeExpiresAt(Instant guaranteeExpiresAt) {
        this.guaranteeExpiresAt = guaranteeExpiresAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "Order[" + publicRef + ", " + status + "]";
    }

    // ------------------------------------------------------ supplier fulfilment ---

    public String getSupplierOrderId() {
        return supplierOrderId;
    }

    public void setSupplierOrderId(String supplierOrderId) {
        this.supplierOrderId = supplierOrderId;
    }

    public String getSupplierStatus() {
        return supplierStatus;
    }

    public String getSupplierAccountCheck() {
        return supplierAccountCheck;
    }

    public String getSupplierEconomyState() {
        return supplierEconomyState;
    }

    public Long getSupplierAmountOrdered() {
        return supplierAmountOrdered;
    }

    public Long getSupplierAmountDelivered() {
        return supplierAmountDelivered;
    }

    public Instant getSupplierPolledAt() {
        return supplierPolledAt;
    }

    public int getSupplierDispatchAttempts() {
        return supplierDispatchAttempts;
    }

    public void recordDispatchAttempt() {
        this.supplierDispatchAttempts++;
    }

    /** One place to write a poll result, so the timestamp can never drift from the values. */
    public void recordSupplierStatus(String status, String accountCheck, String economyState,
                                     Long amountOrdered, Long amountDelivered, Instant at) {
        this.supplierStatus = status;
        this.supplierAccountCheck = accountCheck;
        this.supplierEconomyState = economyState;
        this.supplierAmountOrdered = amountOrdered;
        this.supplierAmountDelivered = amountDelivered;
        this.supplierPolledAt = at;
    }
}
