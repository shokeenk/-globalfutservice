package com.globalfutservice.orders.result;

import com.globalfutservice.domain.money.Currency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * What an order achieved against what it ordered, and what the difference settles to (V47).
 *
 * <p>Changed only through {@link OrderResultService}. Once a settlement is Paid or Issued
 * the row is locked: every method that would change the result refuses.
 */
@Entity
@Table(name = "order_result")
public class OrderResultEntity {

    public enum Status {
        /** Nothing owed: every win was achieved, or a below-tier result settled at zero. */
        NONE,
        /** Owed, not yet settled. */
        PENDING,
        /** Refund sent, confirmed by an admin. */
        PAID,
        /** Store credit added to the customer's balance. */
        ISSUED,
        /** The order was refunded in full instead. */
        CANCELLED
    }

    public enum Method {
        REFUND, STORE_CREDIT
    }

    public enum SetBy {
        CUSTOMER, ADMIN, AUTOMATIC
    }

    @Id
    @Column(name = "order_id")
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ResultKind kind;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, updatable = false)
    private Currency currency;

    @Column(name = "target_level", nullable = false)
    private int targetLevel;

    @Column(name = "achieved_level", nullable = false)
    private int achievedLevel;

    @Column(name = "below_lowest", nullable = false)
    private boolean belowLowest;

    @Column(name = "ordered_price_minor", nullable = false)
    private long orderedPriceMinor;

    @Column(name = "achieved_price_minor")
    private Long achievedPriceMinor;

    @Column(name = "service_paid_minor", nullable = false)
    private long servicePaidMinor;

    @Column(name = "settlement_minor", nullable = false)
    private long settlementMinor;

    @Enumerated(EnumType.STRING)
    private Method method;

    @Enumerated(EnumType.STRING)
    @Column(name = "method_set_by")
    private SetBy methodSetBy;

    @Column(name = "method_set_at")
    private Instant methodSetAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "choose_by")
    private Instant chooseBy;

    @Column(name = "recorded_by")
    private Long recordedBy;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "settled_by")
    private Long settledBy;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "refund_id")
    private Long refundId;

    @Column(name = "points_removed", nullable = false)
    private long pointsRemoved;

    @Version
    @Column(nullable = false)
    private long version;

    protected OrderResultEntity() {
    }

    public OrderResultEntity(Long orderId, ResultKind kind, Currency currency, int targetLevel,
                             long orderedPriceMinor, long servicePaidMinor, Long recordedBy, Instant at) {
        this.orderId = orderId;
        this.kind = kind;
        this.currency = currency;
        this.targetLevel = targetLevel;
        this.orderedPriceMinor = orderedPriceMinor;
        this.servicePaidMinor = servicePaidMinor;
        this.recordedBy = recordedBy;
        this.recordedAt = at;
        this.updatedAt = at;
        this.status = Status.NONE;
    }

    /** Settled, or replaced by a full refund: nothing about it changes any more. */
    public boolean isLocked() {
        return status == Status.PAID || status == Status.ISSUED || status == Status.CANCELLED;
    }

    public boolean isSettled() {
        return status == Status.PAID || status == Status.ISSUED;
    }

    /** Owed, and nobody has said how yet. */
    public boolean awaitsChoice() {
        return status == Status.PENDING && method == null;
    }

    /**
     * Sets what was achieved and what it is owed. Something newly owed gets a choice
     * deadline from now; something no longer owed loses its method and deadline.
     */
    void setOutcome(int achievedLevel, boolean belowLowest, Long achievedPriceMinor, long settlementMinor,
                    Instant now, java.time.Duration choiceWindow) {
        this.achievedLevel = achievedLevel;
        this.belowLowest = belowLowest;
        this.achievedPriceMinor = achievedPriceMinor;
        this.settlementMinor = settlementMinor;
        if (settlementMinor == 0) {
            this.status = Status.NONE;
            this.method = null;
            this.methodSetBy = null;
            this.methodSetAt = null;
            this.chooseBy = null;
        } else if (status == Status.NONE) {
            this.status = Status.PENDING;
            this.chooseBy = now.plus(choiceWindow);
        }
        this.updatedAt = now;
    }

    void setMethod(Method method, SetBy by, Instant now) {
        this.method = method;
        this.methodSetBy = by;
        this.methodSetAt = now;
        this.updatedAt = now;
    }

    void markPaid(Long by, long refundId, Instant now) {
        this.status = Status.PAID;
        this.settledBy = by;
        this.settledAt = now;
        this.refundId = refundId;
        this.updatedAt = now;
    }

    void markIssued(Long by, Instant now) {
        this.status = Status.ISSUED;
        this.settledBy = by;
        this.settledAt = now;
        this.updatedAt = now;
    }

    /** The order was refunded in full: nothing more is owed on its result. */
    public void cancel(Instant now) {
        this.status = Status.CANCELLED;
        this.chooseBy = null;
        this.updatedAt = now;
    }

    void setPointsRemoved(long pointsRemoved) {
        this.pointsRemoved = pointsRemoved;
    }

    public Long getOrderId() {
        return orderId;
    }

    public ResultKind getKind() {
        return kind;
    }

    public Currency getCurrency() {
        return currency;
    }

    public int getTargetLevel() {
        return targetLevel;
    }

    public int getAchievedLevel() {
        return achievedLevel;
    }

    public boolean isBelowLowest() {
        return belowLowest;
    }

    public long getOrderedPriceMinor() {
        return orderedPriceMinor;
    }

    public Long getAchievedPriceMinor() {
        return achievedPriceMinor;
    }

    public long getServicePaidMinor() {
        return servicePaidMinor;
    }

    public long getSettlementMinor() {
        return settlementMinor;
    }

    public Method getMethod() {
        return method;
    }

    public SetBy getMethodSetBy() {
        return methodSetBy;
    }

    public Instant getMethodSetAt() {
        return methodSetAt;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getChooseBy() {
        return chooseBy;
    }

    public Long getRecordedBy() {
        return recordedBy;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getSettledBy() {
        return settledBy;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public Long getRefundId() {
        return refundId;
    }

    public long getPointsRemoved() {
        return pointsRemoved;
    }
}
