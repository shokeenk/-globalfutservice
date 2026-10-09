package com.globalfutservice.orders.web;

import com.globalfutservice.coaching.CoachingSessionEntity;
import com.globalfutservice.coaching.CoachingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.domain.orders.OrderStateMachine;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.pricing.LineCode;
import com.globalfutservice.fulfilment.VendorOrderLedger;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderEventEntity;
import com.globalfutservice.orders.OrderPaymentState;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.notify.DiscordBotClient;
import com.globalfutservice.notify.discord.DiscordVerificationService;
import com.globalfutservice.orders.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    private final AppProperties props;
    private final DiscordVerificationService verification;
    private final DiscordBotClient bot;
    /** Whether a Payop invoice is still open: its payable-until time against now. */
    private final Clock clock;

    public OrderMapper(ObjectMapper mapper, AppProperties props,
                       DiscordVerificationService verification, DiscordBotClient bot,
                       CoachingService coachingService, VendorOrderLedger vendorOrders,
                       OrderPaymentState paymentState) {
        this(mapper, props, verification, bot, coachingService, vendorOrders, paymentState, Clock.systemUTC());
    }

    /** The constructor Spring uses: with two and neither marked, it looks for one with no arguments. */
    @Autowired
    public OrderMapper(ObjectMapper mapper, AppProperties props,
                       DiscordVerificationService verification, DiscordBotClient bot,
                       CoachingService coachingService, VendorOrderLedger vendorOrders,
                       OrderPaymentState paymentState, Clock clock) {
        this.clock = clock;
        this.paymentState = paymentState;
        this.vendorOrders = vendorOrders;
        this.mapper = mapper;
        this.props = props;
        this.verification = verification;
        this.bot = bot;
        this.coachingService = coachingService;
    }

    /** What a held coin order is waiting for the customer to do. */
    private final VendorOrderLedger vendorOrders;

    private final OrderPaymentState paymentState;

    /** For a coaching order's "1 of 6 booked". */
    private final CoachingService coachingService;

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * How this customer gets to their ticket.
     *
     * <p>Only services that are actually run in Discord get a panel at all — a coin order
     * is tracked on this site and pointing its customer at a ticket would be inventing a
     * step. For the rest it comes down to whether we already know their Discord account:
     * if they signed in with Discord they were let into the channel when it opened, and
     * anyone else has to join and claim the order.
     */
    private OrderDtos.DiscordAccessDto discordAccess(OrderEntity order) {
        if (order.getSku() == Sku.TRADING_SERVICE) {
            return new OrderDtos.DiscordAccessDto("NONE", null, null);
        }
        String invite = props.discordInvite();

        /*
         * Without the bot there is no ticket to let anybody into: the customer messages us
         * with their reference (QUOTE), as they do when we do not know their account.
         */
        if (!bot.isEnabled() || isBlank(props.notifications().discordApplicationId())) {
            return new OrderDtos.DiscordAccessDto("QUOTE", null, invite);
        }

        /*
         * A deep link is only offered to somebody who has actually been granted the
         * channel -- every Discord-authenticated customer once the ticket exists, and
         * anybody staff have let in. Linking a channel the reader cannot open would look
         * like the site was broken.
         */
        Optional<String> ticket = verification.ticketUrl(
                order.getId(), props.notifications().discordGuildId());
        if (ticket.isPresent()) {
            return new OrderDtos.DiscordAccessDto("DIRECT", ticket.get(), invite);
        }
        if (verification.isDiscordAuthenticated(order.getAccountId())) {
            // Signed in with Discord, but the ticket is not open yet -- the payment has
            // not been submitted. They will be let in without doing anything.
            return new OrderDtos.DiscordAccessDto("PENDING", null, invite);
        }
        // We do not know their Discord account: they message us, with their reference.
        // (The /verify command is not offered: Discord refuses to register it.)
        return new OrderDtos.DiscordAccessDto("VERIFY", null, invite);
    }

    /** The order as its customer reads it: the timeline in {@link #toCustomerEventDto} form. */
    public OrderDtos.OrderResponse toResponse(OrderEntity order,
                                              List<OrderEventEntity> timeline,
                                              boolean credentialsSubmitted) {
        return toResponse(order, timeline, credentialsSubmitted, OrderMapper::toCustomerEventDto);
    }

    /** The order as staff read it: every timeline row exactly as it was written. */
    public OrderDtos.OrderResponse toAdminResponse(OrderEntity order,
                                                   List<OrderEventEntity> timeline,
                                                   boolean credentialsSubmitted) {
        return toResponse(order, timeline, credentialsSubmitted, OrderMapper::toEventDto);
    }

    private OrderDtos.OrderResponse toResponse(OrderEntity order,
                                               List<OrderEventEntity> timeline,
                                               boolean credentialsSubmitted,
                                               java.util.function.Function<OrderEventEntity, OrderDtos.OrderEventDto> events) {
        boolean coaching = order.getSku() == com.globalfutservice.domain.catalog.Sku.COACHING;
        OrderPaymentState.View payment = paymentState.of(order);
        /*
         * Live progress, from where the vendor poller writes it. These used to be read off the
         * order's own supplier_amount_* columns, which nothing has written since vendor orders
         * moved to their own table -- so the progress bar on the tracking page never showed.
         */
        VendorOrderLedger.Progress progress = order.getTransferStartedAt() == null || order.getId() == null ? null
                : vendorOrders.progress(order.getId()).orElse(null);
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
                payableTotalMinor(order),
                payableTotal(order).format(),
                lines(order),
                order.getPointsRedeemed(),
                order.getPointsEarned(),
                order.getReferralCode(),
                order.getCouponCode(),
                progress == null ? order.getSupplierAmountDelivered() : progress.deliveredK(),
                progress == null ? order.getSupplierAmountOrdered() : Long.valueOf(progress.orderedK()),
                order.getTransferStartedAt() != null,
                customerActionFor(order),
                order.getCreatedAt(),
                order.getDeliveredAt(),
                order.getGuaranteeExpiresAt(),
                timeline.stream().map(events).toList(),
                coaching ? order.getEaPlatformHandle() : null,
                coaching && order.getCoachingPlatform() != null ? order.getCoachingPlatform().name() : null,
                coaching ? order.getCoachingRank() : null,
                coaching ? order.getCoachingFocus() : null,
                order.getPcLauncher() == null ? null : order.getPcLauncher().name(),
                discordAccess(order),
                coaching ? coachingProgress(order) : null,
                payment.state(),
                payment.payBy());
    }

    private OrderDtos.CoachingProgressDto coachingProgress(OrderEntity order) {
        if (order.getId() == null) {
            return null;
        }
        CoachingService.OrderSessions s = coachingService.sessionsForOrder(order.getId(),
                props.coaching().creditsFor(order.getVariant()));
        return new OrderDtos.CoachingProgressDto(s.booked(), s.total(),
                s.next().map(CoachingSessionEntity::getStartsAt).orElse(null),
                s.next().map(CoachingSessionEntity::getEndsAt).orElse(null),
                s.next().map(x -> x.getStatus().name()).orElse(null),
                s.next().map(CoachingSessionEntity::getCustomerTimezone).orElse(null));
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
                payableTotalMinor(order),
                payableTotal(order).format(),
                order.getCurrency().name(),
                order.getCreatedAt(),
                order.getDeliveredAt(),
                transitions,
                paymentState.of(order).state(),
                order.getTransferStartedAt() != null);
    }

    /** What a line is called when it is the fee of the payment method the customer used. */
    public static final String PAYMENT_FEE = "PAYMENT_FEE";

    /** What the fee line of a method that charges its own fee says, before the method's name. */
    public static final String PAYMENT_FEE_LABEL = "Payment processing fee";

    /** "Payment processing fee (Visa / Mastercard)": the fee, and whose it is. */
    public static String paymentFeeLabel(String method) {
        return isBlank(method) ? PAYMENT_FEE_LABEL : PAYMENT_FEE_LABEL + " (" + method.trim() + ")";
    }

    /**
     * The Payop payment an order is being paid with -- an invoice open for it -- or was paid
     * with: the method, its fee and the total. Absent for every other order, which is charged
     * the card fee its quote carries.
     */
    public record PayopTerms(String method, long feeMinor, long totalMinor) {
    }

    /**
     * Reads the frozen quote back out of the snapshot column.
     *
     * <p>An order being paid through Payop -- an invoice open for it, or paid -- is charged its
     * method's own fee instead of the flat card fee the quote carries: that line gives way to
     * the method's fee, so the lines add up to the total the customer pays.
     */
    public List<OrderDtos.OrderLineDto> lines(OrderEntity order) {
        PayopTerms terms = payopTerms(order).orElse(null);
        return terms == null ? paymentLines(order, null, null) : paymentLines(order, terms.feeMinor(), terms.method());
    }

    /**
     * What the customer pays for the order: the Payop invoice's total while one is open for it
     * or once it is paid -- the 2.5% card fee is never part of it -- and otherwise the order's
     * own total, card fee included.
     */
    public long payableTotalMinor(OrderEntity order) {
        return payopTerms(order).map(PayopTerms::totalMinor).orElse(order.getTotalMinor());
    }

    public Money payableTotal(OrderEntity order) {
        return Money.ofMinor(payableTotalMinor(order), order.getCurrency());
    }

    /**
     * The frozen quote's lines, as they read when paid with a method that charges its own
     * fee of {@code feeMinor} instead of the quote's flat card fee: that line is replaced,
     * never added to, and the fee line names {@code method}. Null keeps the quote's own
     * lines, card fee included.
     */
    public List<OrderDtos.OrderLineDto> paymentLines(OrderEntity order, Long feeMinor, String method) {
        List<OrderDtos.OrderLineDto> out = new ArrayList<>(feeMinor == null ? frozenLines(order) : netLines(order));
        if (feeMinor != null) {
            out.add(new OrderDtos.OrderLineDto(PAYMENT_FEE, paymentFeeLabel(method), feeMinor,
                    Money.ofMinor(feeMinor, order.getCurrency()).format(), isBlank(method) ? null : method.trim()));
        }
        return out;
    }

    /**
     * The order's price before any payment fee: every frozen line but the card fee (and a
     * method's own fee, on an order already paid through Payop). What a Payop method's fee is
     * added to.
     */
    public List<OrderDtos.OrderLineDto> netLines(OrderEntity order) {
        return frozenLines(order).stream()
                .filter(l -> !LineCode.GATEWAY_FEE.name().equals(l.code()) && !PAYMENT_FEE.equals(l.code()))
                .toList();
    }

    private List<OrderDtos.OrderLineDto> frozenLines(OrderEntity order) {
        List<OrderDtos.OrderLineDto> out = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(order.getPriceBreakdown());
            for (JsonNode line : root.path("lines")) {
                out.add(new OrderDtos.OrderLineDto(
                        line.path("code").asText(),
                        line.path("label").asText(),
                        line.path("amountMinor").asLong(),
                        line.path("amountFormatted").asText(),
                        line.path("method").isTextual() ? line.path("method").asText() : null));
            }
        } catch (Exception e) {
            // A malformed snapshot must not take down an order page; the customer still
            // gets the total, which is the number that matters to them.
            log.warn("Could not read price breakdown for order {}", order.getPublicRef());
        }
        return out;
    }

    /**
     * The Payop terms in force on the order, if any: those of the invoice it was paid with, or
     * of an invoice still open for it while it waits for payment. An invoice past its
     * payable-until time no longer counts, so an order whose customer let it lapse reads as it
     * was placed again, card fee and all.
     */
    public Optional<PayopTerms> payopTerms(OrderEntity order) {
        JsonNode fee = paymentFee(order);
        if (fee == null) {
            return Optional.empty();
        }
        boolean paid = order.getPayopInvoiceId() != null;
        if (!paid) {
            Instant until = instant(fee.path("payableUntil"));
            if (order.getStatus() != OrderStatus.AWAITING_PAYMENT || until == null
                    || !clock.instant().isBefore(until) || !fee.path("totalMinor").canConvertToLong()) {
                return Optional.empty();
            }
        }
        String method = fee.path("methodName").isTextual() ? fee.path("methodName").asText() : null;
        long total = paid ? order.getTotalMinor() : fee.path("totalMinor").asLong();
        return Optional.of(new PayopTerms(method, fee.path("feeMinor").asLong(), total));
    }

    private static Instant instant(JsonNode node) {
        try {
            return node.isTextual() ? Instant.parse(node.asText()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private JsonNode paymentFee(OrderEntity order) {
        if (order.getPaymentFee() == null || order.getPaymentFee().isBlank()) {
            return null;
        }
        try {
            JsonNode fee = mapper.readTree(order.getPaymentFee());
            return fee.path("feeMinor").canConvertToLong() ? fee : null;
        } catch (Exception e) {
            log.warn("Could not read the payment fee for order {}", order.getPublicRef());
            return null;
        }
    }

    /**
     * A timeline row as the customer reads it.
     *
     * <p>Whatever part of the system wrote it, a system row is signed "GFS": the customer
     * has no use for which subsystem it was, and one of them was named after the
     * fulfilment partner. Rows the first supplier poll wrote also carried the partner's own
     * codes in their reason ("Supplier reports interrupted — NEW_BACKUP_CODES"); those keep
     * the status change and lose the reason.
     */
    static OrderDtos.OrderEventDto toCustomerEventDto(OrderEventEntity event) {
        OrderDtos.OrderEventDto raw = toEventDto(event);
        if (event.getActorType() != com.globalfutservice.domain.orders.Actor.SYSTEM) {
            return raw;
        }
        String reason = raw.reason() != null && raw.reason().startsWith(LEGACY_SUPPLIER_REASON) ? null : raw.reason();
        return new OrderDtos.OrderEventDto(raw.fromStatus(), raw.toStatus(), raw.actorType(), SYSTEM_LABEL, reason,
                raw.at());
    }

    /** What every system row on a customer's timeline is signed. */
    static final String SYSTEM_LABEL = "GFS";

    /** How the first supplier poll began every reason it wrote. */
    private static final String LEGACY_SUPPLIER_REASON = "Supplier reports ";

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
     * What the customer has to do for a held order, in our own vocabulary
     * ({@code RESUBMIT_SIGN_IN}, {@code FREE_TRANSFER_SLOTS}...), which the storefront words.
     *
     * <p>Only while the order is actually held and its vendor order is waiting for the
     * customer. Null means there is nothing for them to do.
     */
    private String customerActionFor(OrderEntity order) {
        if (order.getStatus() != OrderStatus.ON_HOLD) return null;
        return vendorOrders.customerAction(order.getId()).orElse(null);
    }
}
