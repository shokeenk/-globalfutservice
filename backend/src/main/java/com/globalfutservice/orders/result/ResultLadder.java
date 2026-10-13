package com.globalfutservice.orders.result;

import com.globalfutservice.orders.OrderEntity;

import java.time.Instant;
import java.util.Optional;

/**
 * Where a kind of result gets its levels and their prices. One per {@link ResultKind}.
 */
public interface ResultLadder {

    ResultKind kind();

    /**
     * The ladder an order was sold on, as it stood at {@code pricedAt}; empty when the
     * ordered tier cannot be placed on it -- the order is then delivered as before, with no
     * result to record.
     */
    Optional<Ladder> forOrder(OrderEntity order, Instant pricedAt);
}
