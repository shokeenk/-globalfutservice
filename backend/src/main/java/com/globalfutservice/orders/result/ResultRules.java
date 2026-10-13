package com.globalfutservice.orders.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.orders.OrderEntity;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Which orders are measured by a result, and on what ladder.
 *
 * <p>An order needs one when its service has a {@link ResultKind}, settlement is switched
 * on, and its ordered tier can be placed on its ladder. Every other order -- coins,
 * coaching, Rivals, a Champions tier with no win count -- is delivered exactly as before.
 */
@Component
public class ResultRules {

    private final Map<ResultKind, ResultLadder> ladders = new EnumMap<>(ResultKind.class);
    private final AppProperties props;
    private final ObjectMapper mapper;

    public ResultRules(List<ResultLadder> ladders, AppProperties props, ObjectMapper mapper) {
        for (ResultLadder ladder : ladders) {
            this.ladders.put(ladder.kind(), ladder);
        }
        this.props = props;
        this.mapper = mapper;
    }

    public boolean enabled() {
        return props.champs() != null && props.champs().settlementEnabled();
    }

    /** The ladder this order is measured on, as it stood when the order was priced. */
    public Optional<Ladder> ladderFor(OrderEntity order) {
        if (!enabled()) {
            return Optional.empty();
        }
        Optional<ResultKind> kind = ResultKind.forSku(order.getSku());
        if (kind.isEmpty() || !ladders.containsKey(kind.get())) {
            return Optional.empty();
        }
        return ladders.get(kind.get()).forOrder(order, paidFor(order).pricedAt());
    }

    /** Whether delivering this order needs its result first. */
    public boolean requiresResult(OrderEntity order) {
        return ladderFor(order).isPresent();
    }

    public PaidForService paidFor(OrderEntity order) {
        return PaidForService.of(order, mapper);
    }
}
