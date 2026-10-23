package com.globalfutservice.orders.result;

import com.globalfutservice.coaching.AfterCommit;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.orders.Actor;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.domain.payments.ImageType;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.loyalty.LoyaltyService;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.notify.OrderNotification;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.orders.Settlements;
import com.globalfutservice.payments.RefundEntity;
import com.globalfutservice.payments.RefundMethod;
import com.globalfutservice.payments.RefundRepository;
import com.globalfutservice.storecredit.StoreCreditService;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Champions results and their settlement, from Wins Achieved to the money or credit sent.
 *
 * <p>The rules it holds:
 * <ul>
 *   <li><b>Delivered and settled are separate.</b> Delivering records the result and
 *       nothing else: a settlement is owed from then until an admin pays it or credit is
 *       issued. An order can be Delivered with its refund Pending.</li>
 *   <li><b>Paid only when confirmed.</b> A refund is Paid by an admin who confirms it was
 *       sent and gives its reference, which is also recorded as a settlement refund on the
 *       Payments page.</li>
 *   <li><b>Issued only with the credit.</b> Store credit is Issued in the same transaction
 *       that adds it to the customer's balance; if that fails, it stays Pending.</li>
 *   <li><b>Locked once settled.</b> Until then the wins can be corrected and the amount
 *       follows; after, nothing about the result changes.</li>
 *   <li><b>The customer chooses once,</b> while nobody has; after that only an admin can
 *       change it, and only until it is settled. Unchosen after the window, the default
 *       applies (see {@link SettlementDefaultJob}).</li>
 * </ul>
 */
@Service
public class OrderResultService {

    private static final Logger log = LoggerFactory.getLogger(OrderResultService.class);

    /** The largest proof screenshot accepted, as for a payment screenshot (V47). */
    public static final int MAX_PROOF_BYTES = 5 * 1024 * 1024;

    /** History row kinds the customer's page words itself. */
    public static final String DELIVERED_KIND = "RESULT_DELIVERED";
    public static final String UPDATED_KIND = "RESULT_UPDATED";
    public static final String CHOSEN_KIND = "SETTLEMENT_CHOSEN";
    public static final String METHOD_KIND = "SETTLEMENT_METHOD";
    public static final String DEFAULTED_KIND = "SETTLEMENT_DEFAULTED";
    public static final String PAID_KIND = "SETTLEMENT_PAID";
    public static final String ISSUED_KIND = "SETTLEMENT_ISSUED";
    public static final String ISSUED_AUTO_KIND = "SETTLEMENT_ISSUED_AUTO";
    /** Staff-only rows. */
    public static final String NOTE_KIND = "ADMIN_NOTE";
    public static final String PROOF_KIND = "SETTLEMENT_PROOF";

    private final OrderService orders;
    private final OrderRepository orderRepository;
    private final OrderResultRepository results;
    private final SettlementProofRepository proofs;
    private final ResultRules rules;
    private final StoreCreditService storeCredit;
    private final LoyaltyService loyalty;
    private final RefundRepository refunds;
    private final AccountRepository accounts;
    private final NotificationService notifications;
    private final AfterCommit afterCommit;
    private final AppProperties props;
    private final Clock clock;

    private final Settlements settlements;

    public OrderResultService(OrderService orders, OrderRepository orderRepository, OrderResultRepository results,
                              SettlementProofRepository proofs, ResultRules rules,
                              StoreCreditService storeCredit, LoyaltyService loyalty, RefundRepository refunds,
                              AccountRepository accounts, NotificationService notifications,
                              AfterCommit afterCommit, AppProperties props, Clock clock) {
        this.orders = orders;
        this.orderRepository = orderRepository;
        this.results = results;
        this.settlements = new Settlements(loyalty, storeCredit, results);
        this.proofs = proofs;
        this.rules = rules;
        this.storeCredit = storeCredit;
        this.loyalty = loyalty;
        this.refunds = refunds;
        this.accounts = accounts;
        this.notifications = notifications;
        this.afterCommit = afterCommit;
        this.props = props;
        this.clock = clock;
    }

    /** Who is acting, for the history and the record, and whether they are an admin. */
    public record Staff(Long id, String label, boolean admin) {
    }

    // ------------------------------------------------------------------ reading

    /** Whether delivering this order records a result first. */
    @Transactional(readOnly = true)
    public boolean applies(OrderEntity order) {
        return results.existsById(order.getId()) || rules.requiresResult(order);
    }

    @Transactional(readOnly = true)
    public ResultDtos.AdminView adminView(OrderEntity order) {
        Optional<OrderResultEntity> recorded = results.findById(order.getId());
        Optional<Ladder> ladder = rules.ladderFor(order);
        PaidForService paid = rules.paidFor(order);
        long credit = order.getStoreCreditMinor();
        if (ladder.isEmpty() && recorded.isEmpty()) {
            return new ResultDtos.AdminView(false, whyNot(order), null, order.getCurrency().name(), null, null,
                    List.of(), 0L, null, paid.servicePaidMinor(), format(paid.servicePaidMinor(), order),
                    credit, null);
        }
        List<ResultDtos.Option> options = new ArrayList<>();
        Integer target = null;
        Integer lowest = null;
        long orderedPrice = 0;
        long servicePaid = recorded.map(OrderResultEntity::getServicePaidMinor).orElse(paid.servicePaidMinor());
        if (ladder.isPresent()) {
            Ladder l = ladder.get();
            target = l.target();
            lowest = l.lowest();
            orderedPrice = l.orderedPriceMinor();
            for (int level : l.options()) {
                long achievedPrice = l.rungFor(level).map(Ladder.Rung::priceMinor).orElse(0L);
                long settlement = level >= l.target() ? 0L
                        : SettlementCalculator.settlement(l.orderedPriceMinor(), achievedPrice, servicePaid);
                options.add(new ResultDtos.Option(level, achievedPrice, format(achievedPrice, order),
                        format(Math.max(0L, l.orderedPriceMinor() - achievedPrice), order),
                        settlement, format(settlement, order),
                        SettlementCalculator.refundAllowed(settlement, servicePaid, credit)));
            }
        }
        if (recorded.isPresent()) {
            target = recorded.get().getTargetLevel();
            orderedPrice = recorded.get().getOrderedPriceMinor();
        }
        return new ResultDtos.AdminView(true, null, ResultKind.CHAMPS_WINS.name(), order.getCurrency().name(),
                target, lowest, options, orderedPrice, format(orderedPrice, order), servicePaid,
                format(servicePaid, order), credit, recorded.map(r -> recordedView(r, order)).orElse(null));
    }

    private ResultDtos.Recorded recordedView(OrderResultEntity r, OrderEntity order) {
        Optional<RefundEntity> refund = r.getRefundId() == null ? Optional.empty() : refunds.findById(r.getRefundId());
        Optional<SettlementProofEntity> proof = proofs.findById(r.getOrderId());
        return new ResultDtos.Recorded(
                r.getAchievedLevel(),
                r.isBelowLowest(),
                r.getAchievedPriceMinor(),
                r.getAchievedPriceMinor() == null ? null : format(r.getAchievedPriceMinor(), order),
                r.getSettlementMinor(),
                format(r.getSettlementMinor(), order),
                r.getMethod() == null ? null : r.getMethod().name(),
                r.getMethodSetBy() == null ? null : r.getMethodSetBy().name(),
                r.getMethodSetAt(),
                r.getStatus().name(),
                r.awaitsChoice() ? r.getChooseBy() : null,
                r.getRecordedAt(),
                r.getSettledAt(),
                r.getSettledBy() == null ? null : nameOf(r.getSettledBy()),
                refund.map(f -> f.getMethod().name()).orElse(null),
                refund.map(RefundEntity::getReference).orElse(null),
                r.getPointsRemoved(),
                SettlementCalculator.refundAllowed(r.getSettlementMinor(), r.getServicePaidMinor(),
                        order.getStoreCreditMinor()),
                r.isLocked(),
                proof.map(p -> new ResultDtos.Proof(p.getContentType(), p.getSizeBytes(), p.getUploadedAt()))
                        .orElse(null));
    }

    private String whyNot(OrderEntity order) {
        if (ResultKind.forSku(order.getSku()).isEmpty()) {
            return "Only Champions orders record wins achieved.";
        }
        if (!rules.enabled()) {
            return "Champions settlement is switched off (GFS_CHAMPS_SETTLEMENT_ENABLED).";
        }
        return "This order's tier has no number of wins on the price list it was sold from, so it is "
                + "delivered without a result. Settle any difference by hand.";
    }

    @Transactional(readOnly = true)
    public Optional<SettlementProofEntity> proof(OrderEntity order) {
        return proofs.findById(order.getId());
    }

    // ------------------------------------------------------------------ delivering

    /**
     * Records the result and delivers the order, in one transaction. Delivering settles
     * nothing: anything owed is Pending from here.
     */
    @Transactional
    public OrderEntity deliver(OrderEntity order, ResultDtos.ResultInput input, String reason, Staff actor) {
        Ladder ladder = rules.ladderFor(order).orElseThrow(() -> new ApiExceptions.ConflictException(
                "no_result", "This order is delivered without a result."));
        if (results.existsById(order.getId())) {
            throw new ApiExceptions.ConflictException("result_recorded", "Wins Achieved is already recorded.");
        }
        if (input == null || (input.achievedLevel() == null)) {
            throw new ApiExceptions.ConflictException("wins_achieved_required",
                    "Choose Wins Achieved before marking this order delivered.");
        }
        Instant now = clock.instant();
        PaidForService paid = rules.paidFor(order);
        OrderResultEntity result = new OrderResultEntity(order.getId(), ladder.kind(), order.getCurrency(),
                ladder.target(), ladder.orderedPriceMinor(), paid.servicePaidMinor(), actor.id(), now);
        Outcome outcome = outcome(ladder, paid.servicePaidMinor(), input.achievedLevel(), input.below(),
                input.manualAmountMinor(), input.note());
        result.setOutcome(outcome.level(), outcome.below(), outcome.achievedPrice(), outcome.settlement(), now,
                choiceWindow());
        results.saveAndFlush(result);

        noteIfAny(order, actor, outcome.below()
                ? belowNote(outcome, order, input.note()) : blankToNull(input.note()));

        OrderEntity delivered = orders.transition(order, OrderStatus.DELIVERED, Actor.OPERATOR, actor.id(),
                actor.label(), blankToNull(reason), DELIVERED_KIND, String.valueOf(outcome.level()));
        log.info("Order {} delivered with {} of {} wins; settlement {}", order.getPublicRef(), outcome.level(),
                ladder.target(), outcome.settlement());
        return delivered;
    }

    private record Outcome(int level, boolean below, Long achievedPrice, long settlement) {
    }

    /** Checks a result against the ladder and works out what it is owed. */
    private Outcome outcome(Ladder ladder, long servicePaid, Integer level, boolean below, Long manualAmount,
                            String note) {
        if (level == null) {
            throw new ApiExceptions.BadRequestException("Choose Wins Achieved.");
        }
        if (below) {
            if (level >= ladder.lowest()) {
                throw new ApiExceptions.BadRequestException(
                        "Below the lowest tier is fewer than " + ladder.lowest() + " wins.");
            }
            if (manualAmount == null) {
                throw new ApiExceptions.BadRequestException("Enter the settlement amount.");
            }
            if (blankToNull(note) == null) {
                throw new ApiExceptions.BadRequestException("Say why, in the admin note: it is required below "
                        + "the lowest tier.");
            }
            try {
                return new Outcome(level, true, null, SettlementCalculator.manual(manualAmount, servicePaid));
            } catch (IllegalArgumentException e) {
                throw new ApiExceptions.BadRequestException("The settlement can be at most "
                        + Money.ofMinor(servicePaid, ladder.currency()).format()
                        + ", what was paid for the service.");
            }
        }
        if (level < ladder.lowest() || level > ladder.target()) {
            throw new ApiExceptions.BadRequestException("Wins Achieved is between " + ladder.lowest() + " and "
                    + ladder.target() + ", or below the lowest tier.");
        }
        long achievedPrice = ladder.rungFor(level).map(Ladder.Rung::priceMinor).orElseThrow();
        long settlement = level >= ladder.target() ? 0L
                : SettlementCalculator.settlement(ladder.orderedPriceMinor(), achievedPrice, servicePaid);
        return new Outcome(level, false, achievedPrice, settlement);
    }

    // ------------------------------------------------------------------ changing

    /**
     * Corrects the result, sets the method (admin only) or adds a note, before the
     * settlement is paid or issued.
     */
    @Transactional
    public void update(OrderEntity order, ResultDtos.ResultUpdate in, Staff actor) {
        OrderResultEntity result = requireOpen(order);
        Instant now = clock.instant();

        boolean changesResult = in.achievedLevel() != null
                && (in.achievedLevel() != result.getAchievedLevel()
                || Boolean.TRUE.equals(in.belowLowest()) != result.isBelowLowest()
                || (Boolean.TRUE.equals(in.belowLowest()) && in.manualAmountMinor() != null
                && in.manualAmountMinor() != result.getSettlementMinor()));
        if (changesResult) {
            Ladder ladder = rules.ladderFor(order).orElseThrow(() -> new ApiExceptions.ConflictException(
                    "no_result", "This order's price list can no longer be read; settle by hand."));
            Outcome outcome = outcome(ladder, result.getServicePaidMinor(), in.achievedLevel(),
                    Boolean.TRUE.equals(in.belowLowest()), in.manualAmountMinor(), in.note());
            result.setOutcome(outcome.level(), outcome.below(), outcome.achievedPrice(), outcome.settlement(), now,
                    choiceWindow());
            results.saveAndFlush(result);
            orders.note(order, Actor.OPERATOR, actor.id(), actor.label(),
                    "Wins achieved changed to " + outcome.level() + ". Settlement "
                            + format(outcome.settlement(), order) + ".",
                    UPDATED_KIND, String.valueOf(outcome.level()), false);
            if (outcome.below()) {
                noteIfAny(order, actor, belowNote(outcome, order, in.note()));
            } else {
                noteIfAny(order, actor, blankToNull(in.note()));
            }
        } else {
            noteIfAny(order, actor, blankToNull(in.note()));
        }

        if (in.method() != null && !in.method().isBlank()) {
            if (!actor.admin()) {
                throw new ApiExceptions.ForbiddenException("Only an admin can set the settlement method.");
            }
            OrderResultEntity.Method method = parseMethod(in.method());
            if (result.getStatus() != OrderResultEntity.Status.PENDING) {
                throw new ApiExceptions.ConflictException("nothing_owed", "Nothing is owed on this order.");
            }
            if (method != result.getMethod()) {
                requireAllowed(result, order, method);
                result.setMethod(method, OrderResultEntity.SetBy.ADMIN, now);
                results.saveAndFlush(result);
                orders.note(order, Actor.OPERATOR, actor.id(), actor.label(),
                        "Settlement method set to " + label(method) + ".", METHOD_KIND, method.name(), false);
            }
        }
    }

    /** The customer's one choice, while nobody has made one. */
    @Transactional
    public void choose(OrderEntity order, String rawMethod, Long accountId) {
        OrderResultEntity result = results.findById(order.getId()).orElseThrow(() ->
                new ApiExceptions.NotFoundException("No such order."));
        if (!result.awaitsChoice()) {
            throw new ApiExceptions.ConflictException("already_chosen",
                    "This has already been decided. For help, contact Live Chat Support.");
        }
        OrderResultEntity.Method method = parseMethod(rawMethod);
        requireAllowed(result, order, method);
        result.setMethod(method, OrderResultEntity.SetBy.CUSTOMER, clock.instant());
        results.saveAndFlush(result);
        orders.note(order, Actor.CUSTOMER, accountId, null,
                "Customer chose " + (method == OrderResultEntity.Method.REFUND ? "a refund." : "GFS Store Credit."),
                CHOSEN_KIND, method.name(), false);
    }

    // ------------------------------------------------------------------ settling

    /**
     * Marks the refund Paid: only when an admin confirms it was actually sent and gives its
     * reference. Also recorded as a settlement refund, so it shows on the Payments page.
     */
    @Transactional
    public void markPaid(OrderEntity given, ResultDtos.PaidRequest request, Staff actor) {
        requireAdmin(actor);
        OrderEntity order = current(given);
        OrderResultEntity result = requireOpen(order);
        settlements.requireNone(order.getId());
        if (!request.confirmed()) {
            throw new ApiExceptions.BadRequestException("Confirm the refund has actually been sent.");
        }
        if (result.getStatus() != OrderResultEntity.Status.PENDING
                || result.getMethod() != OrderResultEntity.Method.REFUND) {
            throw new ApiExceptions.ConflictException("not_a_refund",
                    "Set the settlement method to Refund before marking it Paid.");
        }
        RefundMethod rail;
        try {
            rail = RefundMethod.valueOf(request.method().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiExceptions.BadRequestException("Unknown refund method.");
        }
        String reference = request.reference().trim();
        if (reference.isEmpty()) {
            throw new ApiExceptions.BadRequestException("The refund's reference is required.");
        }
        Money amount = Money.ofMinor(result.getSettlementMinor(), result.getCurrency());
        RefundEntity refund;
        try {
            refund = refunds.saveAndFlush(new RefundEntity(order.getId(), result.getSettlementMinor(),
                    result.getCurrency(), rail, reference,
                    "Champions settlement: " + result.getAchievedLevel() + " of " + result.getTargetLevel()
                            + " wins achieved", actor.id(), RefundEntity.Kind.SETTLEMENT));
        } catch (DataIntegrityViolationException raced) {
            throw new ApiExceptions.ConflictException("already_paid", "This refund is already recorded.");
        }
        Instant now = clock.instant();
        result.markPaid(actor.id(), refund.getId(), now);
        takeBackPoints(order, result, actor.id());
        results.saveAndFlush(result);
        orders.note(order, Actor.OPERATOR, actor.id(), actor.label(),
                "Refund of " + amount.format() + " paid by " + rail.label() + ", reference " + reference + ".",
                PAID_KIND, amount.format(), false);
        tellSettled(order);
        log.info("Admin {} marked the {} settlement refund on {} paid", actor.label(), amount.format(),
                order.getPublicRef());
    }

    /** Issues the store credit, adding it to the customer's balance in the same transaction. */
    @Transactional
    public void issue(OrderEntity given, ResultDtos.IssueRequest request, Staff actor) {
        requireAdmin(actor);
        OrderEntity order = current(given);
        OrderResultEntity result = requireOpen(order);
        settlements.requireNone(order.getId());
        if (!request.confirmed()) {
            throw new ApiExceptions.BadRequestException("Confirm the store credit to add.");
        }
        if (result.getStatus() != OrderResultEntity.Status.PENDING
                || result.getMethod() != OrderResultEntity.Method.STORE_CREDIT) {
            throw new ApiExceptions.ConflictException("not_store_credit",
                    "Set the settlement method to GFS Store Credit before issuing it.");
        }
        issueCredit(order, result, actor.id(), actor.label(), false);
    }

    /**
     * The choice window has closed with nothing chosen: the default applies. Store credit is
     * issued there and then; a refund default is set for an admin to pay.
     *
     * @return whether anything changed
     */
    @Transactional
    public boolean applyDefault(Long orderId) {
        Instant now = clock.instant();
        Optional<OrderResultEntity> found = results.findById(orderId);
        if (found.isEmpty() || !found.get().awaitsChoice() || found.get().getChooseBy() == null
                || found.get().getChooseBy().isAfter(now)) {
            return false;
        }
        OrderResultEntity result = found.get();
        OrderEntity order = orderRepository.findById(orderId).orElse(null);
        if (order == null || EnumSet.of(OrderStatus.REFUNDED, OrderStatus.CREDITED).contains(order.getStatus())) {
            return false;
        }
        Optional<String> settled = settlements.existing(orderId);
        if (settled.isPresent()) {
            log.warn("Order {}: no settlement default; {}", order.getPublicRef(), settled.get());
            return false;
        }
        int days = props.champs().choiceDays();
        if (props.champs().defaultIsStoreCredit() && order.getAccountId() != null) {
            result.setMethod(OrderResultEntity.Method.STORE_CREDIT, OrderResultEntity.SetBy.AUTOMATIC, now);
            issueCredit(order, result, null, "settlement default", true);
            log.info("Store credit issued automatically on order {} after {} days", order.getPublicRef(), days);
            return true;
        }
        if (!SettlementCalculator.refundAllowed(result.getSettlementMinor(), result.getServicePaidMinor(),
                order.getStoreCreditMinor())) {
            log.warn("Order {}: the refund default cannot apply (paid partly with store credit); left for an admin",
                    order.getPublicRef());
            return false;
        }
        result.setMethod(OrderResultEntity.Method.REFUND, OrderResultEntity.SetBy.AUTOMATIC, now);
        results.saveAndFlush(result);
        orders.note(order, Actor.SYSTEM, null, "settlement default",
                "No choice made in " + days + " days: settlement set to Refund.", DEFAULTED_KIND,
                OrderResultEntity.Method.REFUND.name(), false);
        return true;
    }

    private void issueCredit(OrderEntity order, OrderResultEntity result, Long actorId, String actorLabel,
                             boolean automatic) {
        Money amount = Money.ofMinor(result.getSettlementMinor(), result.getCurrency());
        // In this transaction: if adding the credit fails, nothing below happens and the
        // settlement stays Pending.
        storeCredit.issue(order.getAccountId(), order.getId(), result.getCurrency(), result.getSettlementMinor(),
                order.getPublicRef(), actorId);
        Instant now = clock.instant();
        result.markIssued(actorId, now);
        takeBackPoints(order, result, actorId);
        results.saveAndFlush(result);
        if (automatic) {
            orders.note(order, Actor.SYSTEM, null, actorLabel, "GFS Store Credit of " + amount.format()
                            + " issued automatically after " + props.champs().choiceDays() + " days.",
                    ISSUED_AUTO_KIND, amount.format(), false);
        } else {
            orders.note(order, Actor.OPERATOR, actorId, actorLabel,
                    "GFS Store Credit of " + amount.format() + " issued.", ISSUED_KIND, amount.format(), false);
        }
        tellSettled(order);
    }

    /**
     * Takes back the share of the order's points the settlement paid for: from the order
     * while they are still pending, from the balance once they have landed.
     */
    private void takeBackPoints(OrderEntity order, OrderResultEntity result, Long actorId) {
        long remove = SettlementCalculator.pointsRemoved(order.getPointsEarned(), result.getSettlementMinor(),
                result.getServicePaidMinor());
        if (remove <= 0) {
            return;
        }
        if (order.getPointsSettledAt() == null) {
            order.setPointsEarned(order.getPointsEarned() - remove);
            orderRepository.save(order);
            result.setPointsRemoved(remove);
        } else {
            result.setPointsRemoved(loyalty.deductForSettlement(order.getAccountId(), order.getId(), remove,
                    order.getPublicRef(), actorId));
        }
    }

    /** The settlement email, once the settlement has committed; a failure to send changes nothing. */
    private void tellSettled(OrderEntity order) {
        OrderNotification notice = orders.notificationFor(order);
        afterCommit.run("settlement email for " + order.getPublicRef(), () -> notifications.settlementSettled(notice));
    }

    // ------------------------------------------------------------------ proof

    /** Attaches, or replaces, the settlement screenshot. JPG or PNG, 5 MB at most. Admin only. */
    @Transactional
    public void attachProof(OrderEntity order, byte[] data, Staff actor) {
        requireAdmin(actor);
        OrderResultEntity result = results.findById(order.getId()).orElseThrow(() ->
                new ApiExceptions.ConflictException("no_result", "Record Wins Achieved first."));
        if (result.getSettlementMinor() == 0 || result.getStatus() == OrderResultEntity.Status.CANCELLED) {
            throw new ApiExceptions.ConflictException("nothing_owed", "Nothing is settled on this order.");
        }
        if (data == null || data.length == 0) {
            throw new ApiExceptions.BadRequestException("empty_file", "That file was empty.");
        }
        if (data.length > MAX_PROOF_BYTES) {
            throw new ApiExceptions.BadRequestException("file_too_large", "That image is larger than 5 MB.");
        }
        ImageType type = ImageType.sniff(data);
        if (type != ImageType.JPEG && type != ImageType.PNG) {
            throw new ApiExceptions.BadRequestException("unsupported_image", "Attach a JPG or PNG screenshot.");
        }
        Instant now = clock.instant();
        SettlementProofEntity proof = proofs.findById(order.getId())
                .map(existing -> {
                    existing.replaceWith(type.mediaType(), data, actor.id(), now);
                    return existing;
                })
                .orElseGet(() -> new SettlementProofEntity(order.getId(), type.mediaType(), data, actor.id(), now));
        proofs.saveAndFlush(proof);
        orders.note(order, Actor.OPERATOR, actor.id(), actor.label(),
                "Settlement proof attached (" + type.mediaType() + ", " + (data.length / 1024) + " KB).",
                PROOF_KIND, null, true);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The order as it is now, in this transaction. Controllers load it before the transaction
     * starts, so the copy they pass is detached and may be behind: saving that copy could
     * overwrite a newer row, or fail its version check as a 500.
     */
    private OrderEntity current(OrderEntity order) {
        return orderRepository.findById(order.getId())
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such order."));
    }

    private OrderResultEntity requireOpen(OrderEntity order) {
        OrderResultEntity result = results.findById(order.getId()).orElseThrow(() ->
                new ApiExceptions.ConflictException("no_result", "Record Wins Achieved first."));
        if (result.isLocked()) {
            throw new ApiExceptions.ConflictException("result_locked",
                    "This settlement is " + result.getStatus().name().toLowerCase(Locale.ROOT)
                            + " and can no longer change.");
        }
        return result;
    }

    private void requireAllowed(OrderResultEntity result, OrderEntity order, OrderResultEntity.Method method) {
        if (result.getStatus() != OrderResultEntity.Status.PENDING) {
            throw new ApiExceptions.ConflictException("nothing_owed", "Nothing is owed on this order.");
        }
        if (method == OrderResultEntity.Method.REFUND && !SettlementCalculator.refundAllowed(
                result.getSettlementMinor(), result.getServicePaidMinor(), order.getStoreCreditMinor())) {
            throw new ApiExceptions.ConflictException("refund_not_allowed",
                    "This order was paid partly with GFS Store Credit, so the difference can only be store credit.");
        }
    }

    private static void requireAdmin(Staff actor) {
        if (!actor.admin()) {
            throw new ApiExceptions.ForbiddenException("Only an admin can do that.");
        }
    }

    private void noteIfAny(OrderEntity order, Staff actor, String note) {
        if (note != null) {
            orders.note(order, Actor.OPERATOR, actor.id(), actor.label(), note, NOTE_KIND, null, true);
        }
    }

    private static String belowNote(Outcome outcome, OrderEntity order, String note) {
        return "Below the lowest tier: " + outcome.level() + " wins, settlement "
                + Money.ofMinor(outcome.settlement(), order.getCurrency()).format() + " entered by hand. "
                + note.trim();
    }

    private static OrderResultEntity.Method parseMethod(String raw) {
        try {
            return OrderResultEntity.Method.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiExceptions.BadRequestException("Choose a refund or GFS Store Credit.");
        }
    }

    private static String label(OrderResultEntity.Method method) {
        return method == OrderResultEntity.Method.REFUND ? "Refund" : "GFS Store Credit";
    }

    private Duration choiceWindow() {
        return Duration.ofDays(props.champs().choiceDays());
    }

    private String nameOf(Long accountId) {
        return accounts.findById(accountId)
                .map(a -> a.getDisplayName() != null && !a.getDisplayName().isBlank() ? a.getDisplayName() : a.getEmail())
                .orElse("account " + accountId);
    }

    private static String format(long minor, OrderEntity order) {
        return Money.ofMinor(minor, order.getCurrency()).format();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
