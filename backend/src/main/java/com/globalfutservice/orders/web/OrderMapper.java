package com.globalfutservice.orders.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.domain.orders.OrderStateMachine;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.domain.orders.SupplierStatusMapper;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderEventEntity;
import com.globalfutservice.orders.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Entity to wire shape.
 *
 * <p>Kept as an explicit mapper rather than serialising entities directly. Returning a
 * JPA entity from a controller means every column added later is published to the
 * internet by default — which is how password hashes and internal notes end up in
 * responses.
 */
@Component
public class OrderMapper {

    private static final Logger log = LoggerFactory.getLogger(OrderMapper.class);

    private final ObjectMapper mapper;

    public OrderMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public OrderDtos.OrderResponse toResponse(OrderEntity order,
                                              List<OrderEventEntity> timeline,
                                              boolean credentialsSubmitted) {
        return new OrderDtos.OrderResponse(
                order.getPublicRef(),
                order.getStatus().name(),
                statusLabel(order.getStatus()),
                nextAction(order, credentialsSubmitted),
                OrderService.describe(order),
                order.getSku().name(),
                order.getPlatform() == null ? null : order.getPlatform().name(),
                order.getVariant(),
                order.getQuantity(),
                order.getDeliveryMethod().name(),
                order.requiresCredentials(),
                credentialsSubmitted,
                order.getCurrency().name(),
                order.getTotalMinor(),
                order.total().format(),
                lines(order),
                order.getPointsRedeemed(),
                order.getPointsEarned(),
                order.getReferralCode(),
                order.getSupplierAmountDelivered(),
                order.getSupplierAmountOrdered(),
                customerActionFor(order),
                order.getCreatedAt(),
                order.getDeliveredAt(),
                order.getGuaranteeExpiresAt(),
                timeline.stream().map(OrderMapper::toEventDto).toList());
    }

    public OrderDtos.AdminOrderSummary toAdminSummary(OrderEntity order, boolean credentialsHeld) {
        List<String> transitions = OrderStateMachine.operatorTransitions(order.getStatus())
                .stream().map(Enum::name).sorted().toList();
        return new OrderDtos.AdminOrderSummary(
                order.getPublicRef(),
                order.getStatus().name(),
                OrderService.describe(order),
                order.getPlatform() == null ? null : order.getPlatform().name(),
                order.getQuantity(),
                order.getDeliveryMethod().name(),
                credentialsHeld,
                order.getGuestEmail(),
                order.getTotalMinor(),
                order.total().format(),
                order.getCurrency().name(),
                order.getCreatedAt(),
                order.getDeliveredAt(),
                transitions);
    }

    /** Reads the frozen quote back out of the snapshot column. */
    private List<OrderDtos.OrderLineDto> lines(OrderEntity order) {
        List<OrderDtos.OrderLineDto> out = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(order.getPriceBreakdown());
            for (JsonNode line : root.path("lines")) {
                out.add(new OrderDtos.OrderLineDto(
                        line.path("code").asText(),
                        line.path("label").asText(),
                        line.path("amountMinor").asLong(),
                        line.path("amountFormatted").asText()));
            }
        } catch (Exception e) {
            // A malformed snapshot must not take down an order page; the customer still
            // gets the total, which is the number that matters to them.
            log.warn("Could not read price breakdown for order {}", order.getPublicRef());
        }
        return out;
    }

    private static OrderDtos.OrderEventDto toEventDto(OrderEventEntity event) {
        return new OrderDtos.OrderEventDto(
                event.getFromStatus() == null ? null : event.getFromStatus().name(),
                event.getToStatus().name(),
                event.getActorType().name(),
                event.getActorLabel(),
                event.getReason(),
                event.getCreatedAt());
    }

    /**
     * Human-readable status, computed here rather than in the UI so that the storefront,
     * the emails and the admin console cannot describe the same state three ways.
     */
    public static String statusLabel(OrderStatus status) {
        return switch (status) {
            case DRAFT -> "Starting";
            case AWAITING_PAYMENT -> "Waiting for payment";
            case ABANDONED -> "Cancelled";
            case PAID -> "Paid";
            case CREDENTIALS_PENDING -> "Waiting for your details";
            case READY_FOR_DELIVERY -> "In the queue";
            case IN_PROGRESS -> "Being delivered";
            case ON_HOLD -> "On hold";
            case DELIVERED -> "Delivered";
            case COMPLETED -> "Complete";
            case DISPUTED -> "Under review";
            case REFUNDED -> "Refunded";
            case CREDITED -> "Settled as store credit";
        };
    }

    /**
     * What the customer should do next, decided server-side.
     *
     * <p>The frontend must not be reimplementing the state machine to work out whether to
     * show a form — that is precisely how the two drift apart and a customer is shown a
     * "submit your sign-in" box on an order that was delivered yesterday.
     */
    private static String nextAction(OrderEntity order, boolean credentialsSubmitted) {
        return switch (order.getStatus()) {
            case AWAITING_PAYMENT -> "PAY";
            case CREDENTIALS_PENDING -> credentialsSubmitted ? "WAIT" : "SUBMIT_CREDENTIALS";
            case ON_HOLD -> "CONTACT_SUPPORT";
            case DELIVERED -> "ROTATE_PASSWORD";
            case DISPUTED -> "AWAIT_REVIEW";
            default -> "WAIT";
        };
    }

    /**
     * The supplier's stall reason, as something the customer can act on.
     *
     * <p>Only meaningful while an order is actually held — the mapper returns an action
     * for any recognised code, but showing "clear your unassigned items" beside a
     * delivered order would be nonsense. Null means there is nothing to do.
     */
    private static String customerActionFor(OrderEntity order) {
        if (order.getStatus() != OrderStatus.ON_HOLD) return null;
        SupplierStatusMapper.Outcome outcome = SupplierStatusMapper.map(
                order.getStatus(),
                order.getSupplierStatus(),
                order.getSupplierAccountCheck(),
                order.getSupplierEconomyState(),
                false);
        return outcome.action() == SupplierStatusMapper.CustomerAction.NONE
                ? null : outcome.action().name();
    }
}
