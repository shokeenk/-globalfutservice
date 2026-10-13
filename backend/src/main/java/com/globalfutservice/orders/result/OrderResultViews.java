package com.globalfutservice.orders.result;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.orders.OrderEntity;
import org.springframework.stereotype.Component;

/**
 * The result as the customer's tracking page shows it. Read-only and dependency-light, so
 * the order mapper can include it without reaching for the services that change results.
 */
@Component
public class OrderResultViews {

    private final OrderResultRepository results;
    private final AppProperties props;

    public OrderResultViews(OrderResultRepository results, AppProperties props) {
        this.results = results;
        this.props = props;
    }

    /** The order's result for its customer, or null when it has none. */
    public ResultDtos.CustomerView customer(OrderEntity order) {
        if (order.getId() == null || ResultKind.forSku(order.getSku()).isEmpty()) {
            return null;
        }
        return results.findById(order.getId()).map(r -> customer(r, order.getStoreCreditMinor())).orElse(null);
    }

    ResultDtos.CustomerView customer(OrderResultEntity r, long storeCreditUsedMinor) {
        boolean owed = r.getSettlementMinor() > 0;
        return new ResultDtos.CustomerView(
                r.getKind().name(),
                r.getTargetLevel(),
                r.getAchievedLevel(),
                r.isBelowLowest(),
                r.getAchievedLevel() >= r.getTargetLevel(),
                owed ? r.getSettlementMinor() : null,
                owed ? Money.ofMinor(r.getSettlementMinor(), r.getCurrency()).format() : null,
                r.getMethod() == null ? null : r.getMethod().name(),
                r.getStatus().name(),
                r.awaitsChoice() ? r.getChooseBy() : null,
                SettlementCalculator.refundAllowed(r.getSettlementMinor(), r.getServicePaidMinor(), storeCreditUsedMinor),
                defaultMethod());
    }

    String defaultMethod() {
        return props.champs() == null || props.champs().defaultIsStoreCredit() ? "STORE_CREDIT" : "REFUND";
    }
}
