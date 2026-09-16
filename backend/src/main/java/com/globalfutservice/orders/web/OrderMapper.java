package com.globalfutservice.orders.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.domain.orders.OrderStateMachine;
import com.globalfutservice.domain.catalog.Sku;
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
        boolean coaching = order.getSku() == com.globalfutservice.domain.catalog.Sku.COACHING;
        return new OrderDtos.OrderResponse(
                order.getPublicRef(),
                order.getStatus().name(),
                statusLabel(order),
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
                timeline.stream().map(OrderMapper::toEventDto).toList(),
                coaching ? order.getEaPlatformHandle() : null,
                coaching && order.getCoachingPlatform() != null ? order.getCoachingPlatform().name() : null,
                coaching ? order.getCoachingRank() : null,
                coaching ? order.getCoachingFocus() : null,
                order.getPcLauncher() == null ? null : order.getPcLauncher().name());
    }

    public OrderDtos.AdminOrderSummary toAdminSummary(OrderEntity order, boolean credentialsHeld) {
        List<String> transitions = OrderStateMachine.operatorTransitions(order.getStatus())
                .stream().map(Enum::name).sorted().toList();
        return new OrderDtos.AdminOrderSummary(
                order.getPublicRef(),
                order.getStatus().name(),
                order.getSku().name(),
                statusLabel(order),
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
    /**
     * The status in the customer's words.
     *
     * <p>Thirteen internal states, four words a customer needs: <b>Queued</b> once the
     * money is in and the order is waiting its turn, <b>Processing</b> or <b>Being
     * delivered</b> while somebody is working it, <b>Completed</b> at the end. The states
     * that are not part of that line -- unpaid, on hold, disputed, refunded -- keep saying
     * what they actually are, because collapsing "under review" into "processing" would
     * hide the one status a customer most needs to ask about.
     *
     * <p><b>In progress reads differently per service.</b> A coin or boosting order in
     * progress is being delivered: coins are moving, games are being played. A coaching
     * order in progress is a schedule being arranged, and "being delivered" says nothing
     * a customer recognises -- so it is Processing.
     *
     * <p>The internal names are untouched. This is the label layered over them, and the
     * admin console still shows the state itself.
     */
    public static String statusLabel(OrderEntity order) {
        return switch (order.getStatus()) {
            case DRAFT -> "Starting";
            case AWAITING_PAYMENT -> "Waiting for payment";
            case ABANDONED -> "Cancelled";
            // Paid and waiting: one word for the three states between the money landing
            // and somebody picking the order up.
            case PAID, CREDENTIALS_PENDING, READY_FOR_DELIVERY -> "Queued";
            case IN_PROGRESS -> order.getSku() == Sku.COACHING ? "Processing" : "Being delivered";
            case ON_HOLD -> "On hold";
            case DELIVERED, COMPLETED -> "Completed";
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
