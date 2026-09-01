package com.globalfutservice.domain.orders;

/** Thrown when code attempts a lifecycle move the state machine does not permit. */
public class IllegalTransitionException extends RuntimeException {

    private final OrderStatus from;
    private final OrderStatus to;

    public IllegalTransitionException(OrderStatus from, OrderStatus to) {
        super("Illegal order transition: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public OrderStatus from() {
        return from;
    }

    public OrderStatus to() {
        return to;
    }
}
