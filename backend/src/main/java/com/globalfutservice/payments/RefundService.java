package com.globalfutservice.payments;

import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.orders.Actor;
import com.globalfutservice.domain.orders.OrderStateMachine;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.orders.result.OrderResultEntity;
import com.globalfutservice.orders.result.OrderResultRepository;
import com.globalfutservice.orders.result.SettlementCalculator;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Recording money sent back to a customer.
 *
 * <p>The refund row and the order's move to REFUNDED happen in one transaction, so there
 * is never a refund on an order that is not refunded, or the other way round. The move is
 * the ordinary one, with everything it already does: points spent come back, the coupon
 * is released, the sign-in is deleted, and the customer's bell is told.
 *
 * <p>The state machine decides which orders can be refunded, as it always has. An order
 * already delivered goes through a dispute first; that rule was there before this, and a
 * refund form is not a way round it.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final OrderService orders;
    private final RefundRepository refunds;
    private final OrderResultRepository results;

    public RefundService(OrderService orders, RefundRepository refunds, OrderResultRepository results) {
        this.orders = orders;
        this.refunds = refunds;
        this.results = results;
    }

    /**
     * Records the full refund. When part of the order was already settled -- a Champions
     * settlement paid as a refund or as store credit -- the refund is the total less that,
     * and the customer's line says so.
     */
    @Transactional
    public RefundEntity record(String publicRef, RefundMethod method, String reference,
                               String reason, Long adminId, String adminLabel) {
        OrderEntity order = orders.requireAny(publicRef);
        if (refunds.existsByOrderIdAndKind(order.getId(), RefundEntity.Kind.FULL)) {
            throw new ApiExceptions.ConflictException("already_refunded",
                    "A refund is already recorded for " + publicRef + ".");
        }
        OrderStatus status = order.getStatus();
        if (!OrderStateMachine.operatorTransitions(status).contains(OrderStatus.REFUNDED)) {
            throw new ApiExceptions.ConflictException("not_refundable", whyNot(status));
        }

        Optional<OrderResultEntity> settled = results.findById(order.getId()).filter(OrderResultEntity::isSettled);
        long settledMinor = settled.map(OrderResultEntity::getSettlementMinor).orElse(0L);
        long amount = SettlementCalculator.moneyBackOnFullRefund(order.getTotalMinor(), settledMinor);
        if (amount == 0) {
            throw new ApiExceptions.ConflictException("nothing_to_refund",
                    "Everything paid for " + publicRef + " in money was already settled. "
                            + "Nothing is left to refund.");
        }
        Money refunded = Money.ofMinor(amount, order.getCurrency());

        RefundEntity refund;
        try {
            refund = refunds.saveAndFlush(new RefundEntity(order.getId(), amount,
                    order.getCurrency(), method, reference.trim(), reason.trim(), adminId, RefundEntity.Kind.FULL));
        } catch (DataIntegrityViolationException raced) {
            // Two admins recording the same refund at once: the unique index kept one.
            throw new ApiExceptions.ConflictException("already_refunded",
                    "A refund is already recorded for " + publicRef + ".");
        }

        // The customer reads this line on their order page, so it says what they would want
        // to know -- how much, how, and the reference to look for -- and not the reason,
        // which is written for staff.
        String line = "Refunded " + refunded.format() + " by " + method.label()
                + ", reference " + reference.trim() + ".";
        if (settled.isPresent()) {
            line += " " + Money.ofMinor(settledMinor, order.getCurrency()).format() + " was already settled "
                    + (settled.get().getMethod() == OrderResultEntity.Method.STORE_CREDIT
                    ? "as GFS Store Credit." : "as a refund.");
        }
        orders.transition(order, OrderStatus.REFUNDED, Actor.OPERATOR, adminId, adminLabel, line);

        log.info("Admin {} recorded a refund of {} on order {}", adminLabel, refunded.format(), publicRef);
        return refund;
    }

    static String whyNot(OrderStatus status) {
        return switch (status) {
            case DRAFT, AWAITING_PAYMENT, ABANDONED -> "Nothing was paid for this order, so there is nothing to refund.";
            case DELIVERED, COMPLETED -> "A delivered order is refunded through a dispute. Open one on the order page first.";
            case REFUNDED -> "This order is already refunded.";
            case CREDITED -> "This order was settled as store credit.";
            default -> "This order cannot be refunded from " + status.name() + ".";
        };
    }


}
