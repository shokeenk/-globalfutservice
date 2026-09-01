package com.globalfutservice.domain.orders;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The single place that knows which order transitions are legal.
 *
 * <p>The alternative — {@code if (order.getStatus() == PAID)} scattered across six
 * services — is how orders end up delivered twice, refunded after completion, or moved
 * backwards out of DELIVERED by a mis-click in an admin screen. Centralising it means the
 * rules can be read in one screen and pinned by a test that walks every pair.
 *
 * <p>Two invariants are worth stating out loud because money depends on them:
 * <ol>
 *   <li><b>DELIVERED is a one-way door.</b> Nothing transitions back into fulfilment from
 *       it. It closes the refund window and starts the guarantee clock, so reversing it
 *       would rewrite a contract the customer has already been emailed about.</li>
 *   <li><b>Points and affiliate commission settle at COMPLETED, not PAID.</b> Awarding at
 *       payment would mean writing clawback logic for every refund; awarding after the
 *       guarantee window means clawback code never needs to exist.</li>
 * </ol>
 */
public final class OrderStateMachine {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = new EnumMap<>(OrderStatus.class);

    static {
        ALLOWED.put(OrderStatus.DRAFT, EnumSet.of(
                OrderStatus.AWAITING_PAYMENT, OrderStatus.ABANDONED));

        ALLOWED.put(OrderStatus.AWAITING_PAYMENT, EnumSet.of(
                OrderStatus.PAID, OrderStatus.ABANDONED));

        // Straight to READY when the order is fulfilled by player auction and needs no
        // sign-in; via CREDENTIALS_PENDING when it is a comfort trade.
        ALLOWED.put(OrderStatus.PAID, EnumSet.of(
                OrderStatus.CREDENTIALS_PENDING, OrderStatus.READY_FOR_DELIVERY,
                OrderStatus.REFUNDED));

        ALLOWED.put(OrderStatus.CREDENTIALS_PENDING, EnumSet.of(
                OrderStatus.READY_FOR_DELIVERY, OrderStatus.ON_HOLD, OrderStatus.REFUNDED));

        ALLOWED.put(OrderStatus.READY_FOR_DELIVERY, EnumSet.of(
                OrderStatus.IN_PROGRESS, OrderStatus.ON_HOLD, OrderStatus.REFUNDED));

        ALLOWED.put(OrderStatus.IN_PROGRESS, EnumSet.of(
                OrderStatus.DELIVERED, OrderStatus.ON_HOLD, OrderStatus.REFUNDED));

        ALLOWED.put(OrderStatus.ON_HOLD, EnumSet.of(
                OrderStatus.READY_FOR_DELIVERY, OrderStatus.IN_PROGRESS,
                OrderStatus.CREDENTIALS_PENDING, OrderStatus.REFUNDED));

        // One-way door.
        ALLOWED.put(OrderStatus.DELIVERED, EnumSet.of(
                OrderStatus.COMPLETED, OrderStatus.DISPUTED));

        ALLOWED.put(OrderStatus.DISPUTED, EnumSet.of(
                OrderStatus.COMPLETED, OrderStatus.REFUNDED, OrderStatus.CREDITED));

        ALLOWED.put(OrderStatus.COMPLETED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED.put(OrderStatus.ABANDONED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED.put(OrderStatus.REFUNDED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED.put(OrderStatus.CREDITED, EnumSet.noneOf(OrderStatus.class));
    }

    private OrderStateMachine() {
    }

    public static boolean canTransition(OrderStatus from, OrderStatus to) {
        if (from == null || to == null || from == to) {
            return false;
        }
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(OrderStatus.class)).contains(to);
    }

    public static void assertTransition(OrderStatus from, OrderStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalTransitionException(from, to);
        }
    }

    public static Set<OrderStatus> nextStates(OrderStatus from) {
        return Set.copyOf(ALLOWED.getOrDefault(from, EnumSet.noneOf(OrderStatus.class)));
    }

    /**
     * Transitions an operator may trigger by hand. Payment, completion and abandonment
     * are driven by the webhook and by scheduled jobs — exposing them as buttons invites
     * an operator to mark an unpaid order PAID "just to unblock the customer".
     */
    public static Set<OrderStatus> operatorTransitions(OrderStatus from) {
        Set<OrderStatus> operatorSafe = EnumSet.of(
                OrderStatus.READY_FOR_DELIVERY, OrderStatus.IN_PROGRESS, OrderStatus.ON_HOLD,
                OrderStatus.DELIVERED, OrderStatus.DISPUTED, OrderStatus.REFUNDED,
                OrderStatus.CREDITED, OrderStatus.CREDENTIALS_PENDING);
        EnumSet<OrderStatus> out = EnumSet.noneOf(OrderStatus.class);
        for (OrderStatus candidate : nextStates(from)) {
            if (operatorSafe.contains(candidate)) {
                out.add(candidate);
            }
        }
        return out;
    }
}
