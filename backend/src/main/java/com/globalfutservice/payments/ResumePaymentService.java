package com.globalfutservice.payments;

import com.globalfutservice.coaching.CoachRepository;
import com.globalfutservice.coaching.CoachingService;
import com.globalfutservice.coaching.CoachingSessionEntity;
import com.globalfutservice.coaching.CoachingSessionRepository;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.coaching.SessionActor;
import com.globalfutservice.domain.coaching.SessionStatus;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.domain.payments.ClaimStatus;
import com.globalfutservice.domain.payments.ManualPaymentMethod;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderPaymentState;
import com.globalfutservice.orders.PayByDeadline;
import com.globalfutservice.orders.web.OrderDtos;
import com.globalfutservice.orders.web.OrderMapper;
import com.globalfutservice.payments.payop.PayopCheckoutService;
import com.globalfutservice.payments.payop.PayopInvoiceEntity;
import com.globalfutservice.payments.payop.PayopInvoiceRepository;
import com.globalfutservice.payments.payop.PayopStartToken;
import com.globalfutservice.web.ApiExceptions;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Paying for an order that was placed and not paid: the same order, at its frozen price,
 * by the same means as checkout.
 *
 * <p><b>Nothing new is created but the payment.</b> The order keeps its reference, its
 * price and its currency; the customer's display currency never enters into it. Manual
 * methods (UPI, PayPal, crypto) are paid at the order's own total, which carries the card
 * fee the quote froze. A Payop method is paid at that total less the card fee, plus the
 * method's own fee -- one fee line or the other, never both
 * ({@link OrderMapper#paymentLines}).
 *
 * <p><b>Through the existing flows.</b> A claim goes through {@link ManualPaymentService}
 * (the ticket, the "awaiting verification" email and the operator alert follow exactly as
 * at checkout); a Payop payment through {@link PayopCheckoutService} (one active invoice,
 * reuse, the per-order and per-account limits, manual claims waiting while an invoice is
 * payable). What is new is how a Payop method is chosen: by a {@link PayopStartToken} the
 * server issued, so the browser never sends an amount.
 *
 * <p><b>The owner only</b>, and only while the order is {@code AWAITING_PAYMENT}: the
 * controller loads the order with its owner in the query, and every action here refuses
 * any other status. A new Payop invoice is refused once the order's pay-by time has passed
 * ({@link PayByDeadline}); resuming never moves that time.
 */
@Service
public class ResumePaymentService {

    /** Coaching: the slot the order holds, or held. */
    public record CoachingSlot(String state, String coachId, String coachName, Instant startsAt, Instant endsAt,
                               String timezone, Instant holdExpiresAt, String variant) {
        public static final String HELD = "HELD";
        public static final String EXPIRED = "EXPIRED";
        public static final String NONE = "NONE";
    }

    /** What the customer pays by one route, line by line, in the order's currency. */
    public record Breakdown(List<OrderDtos.OrderLineDto> lines, long totalMinor, String totalFormatted) {
    }

    /** One Payop method, priced for this order, and the token that starts paying with it. */
    public record PayopMethod(long methodId, String name, String type, long feeMinor, String feeFormatted,
                              Breakdown breakdown, String token) {
    }

    /**
     * @param netFormatted the order's price without the card fee: what every method's fee is
     *                     added to
     * @param lines        that price line by line: the order's lines without the 2.5% card fee,
     *                     which a Payop payment never carries
     */
    public record PayopOptions(String currency, long netMinor, String netFormatted,
                               List<OrderDtos.OrderLineDto> lines, String unavailable,
                               List<PayopMethod> methods, Instant manualBlockedUntil) {
    }

    /**
     * Everything the payment step needs. For an order not waiting for payment only the
     * first fields are filled: the page then shows that state and offers nothing to pay.
     */
    public record View(String publicRef, String status, String paymentState, Instant payBy,
                       String currency, long amountDueMinor, String amountDueFormatted,
                       Breakdown manual, List<ManualPaymentService.PaymentOption> manualMethods,
                       boolean payopOffered, PayopCheckoutService.PayableInvoice payableInvoice,
                       Instant manualBlockedUntil, Instant claimSubmittedAt,
                       CoachingSlot coaching, boolean signInNeeded) {
    }

    /**
     * Staff's view of one way the customer tried to pay: a Payop invoice or a payment claim.
     * {@code superseded} marks an invoice the customer moved away from, which Payop can still
     * take until {@code payableUntil}.
     */
    public record Attempt(String kind, String method, String status, String note, long totalMinor,
                          String totalFormatted, Long feeMinor, String feeFormatted, Instant at,
                          Instant payableUntil, boolean superseded) {
        public static final String PAYOP = "PAYOP";
        public static final String MANUAL = "MANUAL";
    }

    /**
     * For staff: the method the customer is paying with now -- the latest real attempt, an
     * invoice opened or a claim sent, never a tab merely looked at -- with what that method
     * charges, and every attempt, newest first.
     */
    public record StaffView(Attempt current, Breakdown currentBreakdown, List<Attempt> attempts) {
    }

    private final ManualPaymentService manual;
    private final ManualPaymentClaimRepository claims;
    private final PayopInvoiceRepository invoices;
    private final PayopCheckoutService payop;
    private final PayopStartToken tokens;
    private final PayByDeadline payBy;
    private final OrderPaymentState paymentState;
    private final OrderMapper orderMapper;
    private final CoachingService coaching;
    private final CoachingSessionRepository sessions;
    private final CoachRepository coaches;
    private final CredentialVaultService vault;
    private final AppProperties props;
    private final Clock clock;

    public ResumePaymentService(ManualPaymentService manual, ManualPaymentClaimRepository claims,
                                PayopInvoiceRepository invoices,
                                PayopCheckoutService payop, PayopStartToken tokens, PayByDeadline payBy,
                                OrderPaymentState paymentState, OrderMapper orderMapper, CoachingService coaching,
                                CoachingSessionRepository sessions, CoachRepository coaches,
                                CredentialVaultService vault, AppProperties props, Clock clock) {
        this.manual = manual;
        this.claims = claims;
        this.invoices = invoices;
        this.payop = payop;
        this.tokens = tokens;
        this.payBy = payBy;
        this.paymentState = paymentState;
        this.orderMapper = orderMapper;
        this.coaching = coaching;
        this.sessions = sessions;
        this.coaches = coaches;
        this.vault = vault;
        this.props = props;
        this.clock = clock;
    }

    /* ------------------------------------------------------------------- view --- */

    public View view(OrderEntity order, String language) {
        OrderPaymentState.View state = paymentState.of(order);
        if (order.getStatus() != OrderStatus.AWAITING_PAYMENT) {
            return new View(order.getPublicRef(), order.getStatus().name(), state.state(), null,
                    order.getCurrency().name(), order.getTotalMinor(), order.total().format(),
                    null, List.of(), false, null, null, null, null, false);
        }
        Optional<PayopCheckoutService.PayableInvoice> invoice = payop.payableInvoice(order, language);
        // What is due now: the open Payop invoice's total while there is one, else the order's own.
        return new View(order.getPublicRef(), order.getStatus().name(), state.state(), state.payBy(),
                order.getCurrency().name(), orderMapper.payableTotalMinor(order),
                orderMapper.payableTotal(order).format(),
                manualBreakdown(order),
                manual.optionsFor(order.getSku().name()),
                payopOffered(order),
                invoice.orElse(null),
                payop.claimsBlockedUntil(order.getId()).orElse(null),
                claims.findByOrderIdAndStatus(order.getId(), ClaimStatus.SUBMITTED)
                        .map(ManualPaymentClaimEntity::getSubmittedAt).orElse(null),
                order.getSku() == Sku.COACHING ? coachingSlot(order) : null,
                signInNeeded(order));
    }

    /** UPI, PayPal and crypto: the order's own frozen lines and total, card fee included. */
    Breakdown manualBreakdown(OrderEntity order) {
        return new Breakdown(orderMapper.paymentLines(order, null, null), order.getTotalMinor(),
                order.total().format());
    }

    private boolean payopOffered(OrderEntity order) {
        return props.payop().enabled() && order.getCurrency() != Currency.INR;
    }

    /** A coin order delivered by sign-in whose sign-in is not (or no longer) held. */
    private boolean signInNeeded(OrderEntity order) {
        return order.requiresCredentials() && !vault.hasCredentials(order.getId());
    }

    /* ------------------------------------------------------------------ payop --- */

    /**
     * Every Payop method for the customer's country, each priced for this order with its own
     * fee in place of the card fee, and each with a token to start paying with it.
     */
    public PayopOptions payopOptions(OrderEntity order, String country) {
        requirePending(order);
        PayopCheckoutService.Options o = payop.options(order, country);
        String iso = country.trim().toUpperCase(Locale.ROOT);
        List<PayopMethod> methods = o.methods().stream()
                .map(m -> new PayopMethod(m.methodId(), m.name(), m.type(), m.feeMinor(),
                        Money.ofMinor(m.feeMinor(), o.currency()).format(),
                        new Breakdown(orderMapper.paymentLines(order, m.feeMinor(), m.name()),
                                m.totalMinor(), Money.ofMinor(m.totalMinor(), o.currency()).format()),
                        tokens.issue(order, m.methodId(), iso, m.totalMinor())))
                .toList();
        return new PayopOptions(o.currency().name(), o.netMinor(), Money.ofMinor(o.netMinor(), o.currency()).format(),
                orderMapper.netLines(order), o.unavailable(), methods,
                payop.claimsBlockedUntil(order.getId()).orElse(null));
    }

    /**
     * Pays with the Payop method a token names. Hands back an invoice already open for the
     * same method and price, reopens one the customer moved away from, or opens a new one
     * within the limits.
     */
    public PayopCheckoutService.Started startPayop(OrderEntity order, String token, String language) {
        requirePending(order);
        PayopStartToken.Claims claim = tokens.verify(order, token);
        if (!clock.instant().isBefore(payBy.forOrder(order))) {
            throw new ApiExceptions.ConflictException("pay_by_passed",
                    "The time to pay for this order has passed. Place a new order.");
        }
        return payop.start(order, claim.methodId(), claim.country(), claim.totalMinor(), language);
    }

    /* ----------------------------------------------------------------- manual --- */

    public ManualPaymentClaimEntity submitClaim(OrderEntity order, ManualPaymentMethod method, String reference) {
        requirePending(order);
        return manual.submit(order, method, reference);
    }

    public ManualPaymentProofEntity attachProof(OrderEntity order, byte[] data) {
        requirePending(order);
        return manual.attachProof(order, data);
    }

    /* --------------------------------------------------------------- coaching --- */

    /**
     * The slot this coaching order holds, or the one it held before the hold ran out, or
     * {@code NONE} for an order bought without a slot (the customer books with the credit).
     */
    CoachingSlot coachingSlot(OrderEntity order) {
        List<CoachingSessionEntity> all = sessions.findByOrderIdOrderByIdAsc(order.getId());
        Instant now = clock.instant();
        Optional<CoachingSessionEntity> held = all.stream()
                .filter(s -> s.getStatus() == SessionStatus.PENDING && s.getHoldExpiresAt() != null
                        && s.getHoldExpiresAt().isAfter(now))
                .findFirst();
        if (held.isPresent()) {
            return slot(CoachingSlot.HELD, held.get(), order);
        }
        return all.stream()
                .filter(s -> s.getStatus() == SessionStatus.PENDING || s.getStatus() == SessionStatus.RELEASED)
                .max(Comparator.comparing(CoachingSessionEntity::getId))
                .map(s -> slot(CoachingSlot.EXPIRED, s, order))
                .orElse(new CoachingSlot(CoachingSlot.NONE, null, null, null, null, null, null, order.getVariant()));
    }

    /**
     * Holds a slot again for a coaching order whose hold ran out: the one it had, when no
     * coach and time are given, or the one the customer has just picked. Refused with
     * {@code slot_unavailable} when it has been taken in the meantime -- the customer then
     * picks another. A hold still running is left as it is.
     */
    public CoachingSlot holdSlot(OrderEntity order, String coachId, Instant startsAt, String timezone) {
        requirePending(order);
        if (order.getSku() != Sku.COACHING) {
            throw new ApiExceptions.BadRequestException("not_coaching", "This order has no session to book.");
        }
        CoachingSlot current = coachingSlot(order);
        if (CoachingSlot.HELD.equals(current.state())) {
            return current;
        }
        String coach = coachId != null && !coachId.isBlank() ? coachId.trim() : current.coachId();
        Instant at = startsAt != null ? startsAt : current.startsAt();
        String zone = timezone != null && !timezone.isBlank() ? timezone.trim() : current.timezone();
        if (coach == null || at == null) {
            throw new ApiExceptions.BadRequestException("slot_required", "Pick a date and time for your session.");
        }
        // A lapsed hold the minute-by-minute sweep has not released yet would hold the
        // order's one pending place (a unique index): let it go first.
        coaching.releaseHoldForOrder(order.getId(), SessionActor.SYSTEM, null, "hold expired");
        CoachingSessionEntity hold = coaching.holdForOrder(order.getAccountId(), order.getId(), order.getVariant(),
                coach, at, zone);
        return slot(CoachingSlot.HELD, hold, order);
    }

    private CoachingSlot slot(String state, CoachingSessionEntity s, OrderEntity order) {
        var coach = coaches.findById(s.getCoachId());
        return new CoachingSlot(state, coach.map(c -> c.getPublicId()).orElse(null),
                coach.map(c -> c.getDisplayName()).orElse(null), s.getStartsAt(), s.getEndsAt(),
                s.getCustomerTimezone(), CoachingSlot.HELD.equals(state) ? s.getHoldExpiresAt() : null,
                order.getVariant());
    }

    /* ------------------------------------------------------------------ staff --- */

    public StaffView staffView(OrderEntity order) {
        Currency currency = order.getCurrency();
        Instant now = clock.instant();
        List<Attempt> attempts = new ArrayList<>();
        for (PayopInvoiceEntity a : invoices.findByOrderIdOrderByCreatedAtDesc(order.getId())) {
            boolean superseded = a.getStatus() == PayopInvoiceEntity.Status.EXPIRED
                    && PayopInvoiceEntity.REPLACED.equals(a.getReviewReason());
            attempts.add(new Attempt(Attempt.PAYOP, a.getMethodName(), a.getStatus().name(), a.getReviewReason(),
                    a.getTotalMinor(), Money.ofMinor(a.getTotalMinor(), currency).format(), a.getFeeMinor(),
                    Money.ofMinor(a.getFeeMinor(), currency).format(), a.getCreatedAt(),
                    a.getInvoiceId() != null && a.getExpiresAt().isAfter(now) ? a.getExpiresAt() : null,
                    superseded));
        }
        for (ManualPaymentClaimEntity c : claims.findByOrderIdOrderBySubmittedAtDesc(order.getId())) {
            attempts.add(new Attempt(Attempt.MANUAL, c.getMethod().name(), c.getStatus().name(), c.getReference(),
                    order.getTotalMinor(), order.total().format(), null, null, c.getSubmittedAt(), null, false));
        }
        attempts.sort(Comparator.comparing(Attempt::at, Comparator.nullsLast(Comparator.reverseOrder())));

        // An invoice counts once it was really opened at Payop; a claim once it was sent.
        Optional<Attempt> current = attempts.stream()
                .filter(a -> Attempt.MANUAL.equals(a.kind())
                        || (!"CREATING".equals(a.status()) && !"FAILED".equals(a.status())))
                .findFirst();
        Breakdown breakdown = current.map(a -> Attempt.PAYOP.equals(a.kind())
                        ? new Breakdown(orderMapper.paymentLines(order, a.feeMinor(), a.method()),
                                a.totalMinor(), a.totalFormatted())
                        : manualBreakdown(order))
                .orElse(null);
        return new StaffView(current.orElse(null), breakdown, attempts);
    }

    /* ------------------------------------------------------------------ rules --- */

    private static void requirePending(OrderEntity order) {
        if (order.getStatus() != OrderStatus.AWAITING_PAYMENT) {
            throw new ApiExceptions.ConflictException("payment_not_pending",
                    "This order is not waiting for payment.");
        }
    }
}
