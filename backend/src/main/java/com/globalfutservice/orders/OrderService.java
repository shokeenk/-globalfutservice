package com.globalfutservice.orders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.affiliate.AffiliateService;
import com.globalfutservice.coaching.CoachingService;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.credentials.web.CredentialDtos;
import com.globalfutservice.domain.catalog.CoinAmount;
import com.globalfutservice.domain.catalog.PcLauncher;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.crypto.SecureIds;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.orders.Actor;
import com.globalfutservice.domain.orders.DeliveryMethod;
import com.globalfutservice.domain.orders.OrderStateMachine;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.domain.pricing.LineCode;
import com.globalfutservice.domain.pricing.Quote;
import com.globalfutservice.domain.pricing.QuoteLine;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.loyalty.LoyaltyService;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.notify.feed.CustomerFeedService;
import com.globalfutservice.notify.feed.NotificationKind;
import com.globalfutservice.notify.OrderNotification;
import com.globalfutservice.orders.web.OrderDtos;
import com.globalfutservice.payments.PaymentEntity;
import com.globalfutservice.payments.PaymentGateway;
import com.globalfutservice.payments.PaymentRepository;
import com.globalfutservice.pricing.CouponService;
import com.globalfutservice.pricing.QuoteService;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Order orchestration: creation, lifecycle, and the side effects that hang off each
 * transition.
 *
 * <p>Every state change in this application goes through {@link #transition}. Nothing
 * writes {@code order.setStatus(...)} directly, which is what makes it possible to say
 * with confidence that <i>every</i> change is validated against the state machine and
 * <i>every</i> change leaves an audit row.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final OrderEventRepository events;
    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final QuoteService quoteService;
    private final LoyaltyService loyaltyService;
    private final AffiliateService affiliateService;
    private final CredentialVaultService vaultService;
    private final NotificationService notifications;
    private final AccountRepository accounts;
    private final CoachingService coachingService;
    private final CustomerFeedService feed;
    private final CouponService couponService;
    private final ObjectMapper mapper;
    private final AppProperties props;
    private final Clock clock;

    public OrderService(OrderRepository orders, OrderEventRepository events,
                        PaymentRepository payments, PaymentGateway gateway,
                        QuoteService quoteService, LoyaltyService loyaltyService,
                        AffiliateService affiliateService, CredentialVaultService vaultService,
                        NotificationService notifications, AccountRepository accounts,
                        CoachingService coachingService, CouponService couponService,
                        CustomerFeedService feed,
                        ObjectMapper mapper, AppProperties props, Clock clock) {
        this.feed = feed;
        this.coachingService = coachingService;
        this.couponService = couponService;
        this.orders = orders;
        this.events = events;
        this.payments = payments;
        this.gateway = gateway;
        this.quoteService = quoteService;
        this.loyaltyService = loyaltyService;
        this.affiliateService = affiliateService;
        this.vaultService = vaultService;
        this.notifications = notifications;
        this.accounts = accounts;
        this.mapper = mapper;
        this.props = props;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- creation

    /**
     * Turns an accepted quote into an order and a payment intent.
     *
     * <p>Order of operations matters here. The quote is verified first, then points are
     * debited, then the gateway is called — so a gateway failure leaves nothing
     * half-applied, and the whole method is one transaction that either produces a
     * payable order or nothing at all.
     */
    @Transactional
    public OrderDtos.CreateOrderResponse create(OrderDtos.CreateOrderRequest request,
                                                AccountEntity account) {
        Quote quote = quoteService.verifyOrThrow(request.quote(), account);

        // A quote can become at most one order. Checked here for a clean error message,
        // and enforced by a unique index for the case where two requests race.
        if (orders.existsByQuoteId(quote.quoteId())) {
            throw new ApiExceptions.ConflictException("duplicate_order",
                    "This order has already been placed. Check your email for the reference.");
        }

        DeliveryMethod delivery = resolveDeliveryMethod(request.deliveryMethod(), quote);

        String breakdownJson;
        try {
            breakdownJson = mapper.writeValueAsString(request.quote());
        } catch (Exception e) {
            throw new ApiExceptions.BadRequestException("Your order could not be recorded.");
        }

        OrderEntity order = new OrderEntity(
                SecureIds.orderRef(props.seasonYear()),
                quote.quoteId(),
                quote.season(),
                quote.sku(),
                quote.platform(),
                quote.variant(),
                quote.quantity(),
                delivery,
                quote.currency(),
                quote.subtotal().minor(),
                quote.total().minor(),
                breakdownJson);

        // Record what this was sold as, from the line the engine already built. Taking it
        // from the quote rather than recomposing it is the whole point: one derivation,
        // frozen at purchase, shown identically to the customer, the operator and the
        // payment processor.
        order.setServiceLabel(quote.lines().stream()
                .filter(l -> l.code() == LineCode.BASE)
                .map(QuoteLine::label)
                .findFirst()
                .orElse(null));

        order.setGuestEmail(request.email().trim().toLowerCase(Locale.ROOT));
        order.setGuestPhone(request.phone());
        // Trimmed to null rather than stored empty: "no name given" and "gave an empty
        // name" are different facts and only one of them is true.
        order.setGuestName(blankToNull(request.fullName()));
        order.setDiscordUsername(blankToNull(request.discordUsername()));
        order.setEaPlatformHandle(request.eaPlatformHandle());
        order.setCustomerNote(request.note());
        if (quote.sku() == Sku.COACHING) {
            applyCoachingDetails(order, request);
        }
        if (quote.sku().isBoosting()) {
            applyBoostingDetails(order, request);
        }
        order.setPointsRedeemed(quote.pointsRedeemed());
        order.setPointsEarned(quote.pointsEarned());
        order.setReferralCode(quote.referralCode());
        order.setCouponCode(quote.couponCode());
        if (account != null) {
            order.setAccountId(account.getId());
        }

        try {
            order = orders.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            throw new ApiExceptions.ConflictException("duplicate_order",
                    "This order has already been placed. Check your email for the reference.");
        }

        record(order, null, OrderStatus.DRAFT, Actor.CUSTOMER,
                account == null ? null : account.getId(), request.email(), "Order created");

        /*
         * Claim the coupon inside the same transaction, re-checking the live limit.
         *
         * The quote priced the coupon; it did not reserve it. Between minting that price
         * and this line somebody else may have taken the last redemption, and the only
         * honest response is to refuse the order rather than honour a discount the
         * campaign no longer has. CouponService.redeem throws in that case, which rolls
         * the whole method back — no order, no points debited, no gateway call.
         */
        if (quote.couponCode() != null) {
            // `order` is reassigned by the save above, so it is not effectively final and
            // cannot be captured. Bind the ids first.
            final Long orderId = order.getId();
            final Long buyerId = account == null ? null : account.getId();
            couponService.resolveIdFor(quote.couponCode()).ifPresent(couponId ->
                    couponService.redeem(
                            couponId,
                            quote.couponCode(),
                            orderId,
                            buyerId,
                            discountBpsOf(quote),
                            Math.abs(discountMinorOf(quote))));
        }

        // Debit points inside the same transaction, re-checking the live balance. The
        // quote was a price, not a reservation — the customer may have spent the same
        // points in another tab since it was minted.
        if (account != null && quote.pointsRedeemed() > 0) {
            loyaltyService.redeem(account.getId(), order.getId(),
                    quote.pointsRedeemed(), order.getPublicRef());
        }

        PaymentGateway.GatewayOrder gatewayOrder = gateway.createOrder(
                order.getPublicRef(),
                order.total(),
                order.getGuestEmail(),
                gatewayDescription(order));

        payments.save(new PaymentEntity(order.getId(), gatewayOrder.providerOrderId(),
                order.getTotalMinor(), order.getCurrency()));

        transition(order, OrderStatus.AWAITING_PAYMENT, Actor.SYSTEM, null,
                "gateway", "Payment intent created");

        notifications.orderPlaced(notificationFor(order));

        return new OrderDtos.CreateOrderResponse(
                order.getPublicRef(),
                order.getStatus().name(),
                order.getTotalMinor(),
                order.total().format(),
                order.getCurrency().name(),
                new OrderDtos.PaymentIntent(
                        "RAZORPAY",
                        gatewayOrder.providerOrderId(),
                        gatewayOrder.publicKey(),
                        gatewayOrder.amountMinor(),
                        gatewayOrder.currency(),
                        order.getGuestEmail(),
                        describe(order)));
    }

    /**
     * Comfort trade is only available where the SKU allows it, and player auction is the
     * default everywhere else — the method that needs no credentials should be the one
     * that happens unless somebody deliberately chooses otherwise.
     */
    private DeliveryMethod resolveDeliveryMethod(String requested, Quote quote) {
        // Scheduled SKUs are not negotiable: a coaching order is fulfilled by a calendar,
        // and letting a request body talk one into COMFORT_TRADE would put it in the
        // trading queue and, worse, mark it as an order that may hold an EA sign-in.
        if (quote.sku().isScheduled()) {
            return DeliveryMethod.SCHEDULED_SESSION;
        }
        DeliveryMethod fallback = props.fulfilment().defaultDeliveryMethod();
        if (requested == null || requested.isBlank()) {
            return quote.sku().mayRequireCredentials() ? fallback : DeliveryMethod.PLAYER_AUCTION;
        }
        DeliveryMethod method;
        try {
            method = DeliveryMethod.valueOf(requested.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiExceptions.BadRequestException("Unknown delivery method.");
        }
        if (method.requiresCredentials() && !quote.sku().mayRequireCredentials()) {
            return DeliveryMethod.PLAYER_AUCTION;
        }
        return method;
    }

    // --------------------------------------------------------------- lifecycle

    /**
     * The one door every status change goes through.
     *
     * <p>Validates against the state machine, applies the side effects that belong to the
     * target state, and writes the audit row — in that order, in one transaction.
     */
    @Transactional
    public OrderEntity transition(OrderEntity order, OrderStatus to, Actor actor,
                                  Long actorId, String actorLabel, String reason) {
        OrderStatus from = order.getStatus();
        OrderStateMachine.assertTransition(from, to);

        Instant now = clock.instant();
        order.setStatus(to);

        switch (to) {
            case DELIVERED -> {
                // The contractual moment. Stamped once, never nulled; the guarantee clock
                // and the closure of the refund window both hang off this timestamp, and
                // the customer is emailed to say so.
                order.setDeliveredAt(now);
                order.setGuaranteeExpiresAt(now.plus(props.fulfilment().guaranteeWindow()));
            }
            case COMPLETED -> {
                order.setCompletedAt(now);
                settleRewards(order, now);
                vaultService.purge(order.getId(), "order completed");
            }
            case REFUNDED -> {
                // Points spent on a reversed order go back; points earned have not been
                // granted yet, because they only settle at COMPLETED. That is the whole
                // reason for the delay — there is no clawback path to get wrong.
                if (order.getAccountId() != null && order.getPointsRedeemed() > 0) {
                    loyaltyService.reverseRedemption(order.getAccountId(), order.getId(),
                            order.getPointsRedeemed(), order.getPublicRef());
                }
                // Hand the coupon redemption back. A refunded order did not consume a
                // campaign slot, and leaving it claimed quietly shrinks every campaign by
                // the number of orders that were reversed.
                couponService.release(order.getId());
                vaultService.purge(order.getId(), "order refunded");
            }
            case CREDITED -> {
                // An upheld guarantee claim settled as store credit rather than cash.
                // Credit rides on the existing points wallet: it already has a balance, a
                // statement and a redemption path, so there is no second currency to
                // maintain. The credit is deliberately worth more than the cash option.
                long creditPoints = order.getTotalMinor()
                        * props.fulfilment().guaranteeCreditBps() / 10_000
                        / props.pricing().pointValueMinor();
                loyaltyService.adjust(order.getAccountId(), order.getId(), creditPoints,
                        "Guarantee settled as store credit on " + order.getPublicRef(), actorId);
                vaultService.purge(order.getId(), "guarantee settled as credit");
            }
            case ABANDONED -> {
                if (order.getAccountId() != null && order.getPointsRedeemed() > 0) {
                    loyaltyService.reverseRedemption(order.getAccountId(), order.getId(),
                            order.getPointsRedeemed(), order.getPublicRef());
                }
                // Same for an abandoned checkout, and this is the one that matters most:
                // publicly-shared codes get abandoned far more than they get paid, so a
                // campaign would otherwise drain on carts nobody ever completed.
                couponService.release(order.getId());
                vaultService.purge(order.getId(), "checkout abandoned");
            }
            default -> {
                // No side effects for the intermediate states.
            }
        }

        OrderEntity saved = orders.save(order);
        record(saved, from, to, actor, actorId, actorLabel, reason);

        tellTheCustomer(saved, to);

        if (to == OrderStatus.DELIVERED) {
            notifications.orderDelivered(notificationFor(saved));
        } else if (to == OrderStatus.CREDENTIALS_PENDING) {
            notifications.credentialsNeeded(notificationFor(saved));
        } else if (to == OrderStatus.READY_FOR_DELIVERY) {
            /*
             * The operator's call to action.
             *
             * Deliberately on entry to this state rather than on PAID. An auction
             * order goes PAID -> READY in the same breath, so the alert still lands
             * the moment the money does; a comfort trade sits at
             * CREDENTIALS_PENDING until the sign-in arrives, and telling somebody
             * to start a job they are blocked on is how alerts get muted.
             *
             * It fires again when an order returns here from ON_HOLD, which is
             * correct — that order has just re-entered the queue.
             */
            notifications.readyToFulfil(notificationFor(saved));
        }

        log.info("Order {} {} -> {} by {}", saved.getPublicRef(), from, to, actor);
        return saved;
    }

    /**
     * The same moment, in the customer's own words.
     *
     * <p>Hung off the one transition every status change already goes through, so the bell
     * cannot drift from the order: there is no second place deciding what happened. The
     * operator alert and the customer's line are written from the same event and say
     * different things, because an operator needs "GFS-26-XXXX paid, ready to fulfil" and
     * a customer needs "Payment confirmed".
     *
     * <p>Only the states a customer would act on or ask about are here. PAID on its own is
     * not one of them: an auction order passes through it and lands on READY_FOR_DELIVERY
     * in the same breath, and two notifications a second apart for one event is how a bell
     * teaches people to ignore it.
     */
    private void tellTheCustomer(OrderEntity order, OrderStatus to) {
        String ref = order.getPublicRef();
        String what = describe(order);
        String link = "/track?ref=" + ref;
        switch (to) {
            case AWAITING_PAYMENT -> feed.record(order.getAccountId(), NotificationKind.ORDER_PLACED,
                    "Order placed", what + " — waiting for payment.", link, ref);
            case CREDENTIALS_PENDING -> feed.record(order.getAccountId(), NotificationKind.ACTION_NEEDED,
                    "We need your EA sign-in",
                    "Your order cannot start until we have it. It is encrypted and deleted when the "
                            + "order is done.", link, ref);
            case READY_FOR_DELIVERY -> feed.record(order.getAccountId(), NotificationKind.PAYMENT_CONFIRMED,
                    "Payment confirmed", what + " is queued for delivery.", link, ref);
            case IN_PROGRESS -> feed.record(order.getAccountId(), NotificationKind.STATUS_CHANGED,
                    "Your order has started", "Someone is working on " + what + " now.", link, ref);
            case ON_HOLD -> feed.record(order.getAccountId(), NotificationKind.ACTION_NEEDED,
                    "Your order is on hold", "We have paused " + what + ". Open the order to see why.",
                    link, ref);
            case DELIVERED -> feed.record(order.getAccountId(), NotificationKind.DELIVERED,
                    "Order delivered", what + " is done.", link, ref);
            case COMPLETED -> feed.record(order.getAccountId(), NotificationKind.STATUS_CHANGED,
                    "Order completed", "Your guarantee window on " + what + " has closed and your "
                            + "reward points have landed.", link, ref);
            case REFUNDED -> feed.record(order.getAccountId(), NotificationKind.STATUS_CHANGED,
                    "Order refunded", what + " has been refunded.", link, ref);
            default -> {
                // Draft, abandoned, disputed and credited are either invisible to the
                // customer or arrive through a conversation rather than a bell.
            }
        }
    }

    /**
     * Points and commission settle here, once, when the guarantee window has elapsed.
     *
     * <p>Granting at payment instead would mean writing clawback logic for every refund
     * and every upheld ban claim. Granting after the window means that logic exists only
     * for genuine reversals and is almost never exercised.
     */
    private void settleRewards(OrderEntity order, Instant now) {
        if (order.getPointsSettledAt() != null) {
            return;
        }
        if (order.getAccountId() != null) {
            loyaltyService.award(order.getAccountId(), order.getId(),
                    order.getPointsEarned(), order.getPublicRef());
            accounts.findById(order.getAccountId()).ifPresent(account -> {
                if (account.getFirstCompletedAt() == null) {
                    account.setFirstCompletedAt(now);
                    accounts.save(account);
                }
            });
        }
        affiliateService.accrue(order.getReferralCode(), order.getId(),
                order.getTotalMinor(), order.getCurrency());
        order.setPointsSettledAt(now);
    }

    /**
     * Called by the payment webhook after signature verification.
     *
     * <p>Advances straight past PAID to the next actionable state, so the operator queue
     * only ever contains orders somebody can actually work on.
     */
    @Transactional
    public void markPaid(OrderEntity order, String paymentReference) {
        if (order.getStatus() != OrderStatus.AWAITING_PAYMENT) {
            log.info("Ignoring payment for order {} already in state {}",
                    order.getPublicRef(), order.getStatus());
            return;
        }
        OrderEntity paid = transition(order, OrderStatus.PAID, Actor.SYSTEM, null,
                "gateway", "Payment captured: " + paymentReference);

        // Coaching credits are granted at PAID, unlike loyalty points, which wait for the
        // guarantee window. The customer has bought sessions and needs to book them now;
        // making them wait a week for the credits would make the product unusable. The
        // grant is idempotent at the database level, so a retried webhook cannot double it.
        if (paid.getSku() == Sku.COACHING) {
            int sessions = props.coaching().creditsFor(paid.getVariant());
            // The length is stamped on the credit here, at purchase, because this is the
            // last point at which the variant is known -- booking happens later and sees
            // only a pool of credits.
            coachingService.grantCredits(paid.getAccountId(), paid.getId(), sessions,
                    paid.getPublicRef(), props.coaching().sessionLengthFor(paid.getVariant()));

            // The customer's next step is Discord, so the invite goes out the moment the
            // money is confirmed -- by whichever path confirmed it, gateway or operator.
            notifications.coachingConfirmed(notificationFor(paid));
        }

        /*
         * Three ways out of PAID, not two.
         *
         * An order needing a sign-in used to always wait for one. Now the sign-in may
         * already be sealed -- collected at checkout, before the money -- and asking a
         * customer for something they have already given is the kind of thing that makes
         * people think their details went nowhere. The vault is the authority on whether
         * it arrived.
         */
        boolean needsSignIn = paid.requiresCredentials()
                && !vaultService.hasCredentials(paid.getId());

        OrderStatus next = needsSignIn
                ? OrderStatus.CREDENTIALS_PENDING
                : OrderStatus.READY_FOR_DELIVERY;
        transition(paid, next, Actor.SYSTEM, null, "gateway",
                needsSignIn
                        ? "Waiting for the customer's sign-in"
                        : "Queued for delivery");
    }

    @Transactional
    public void markPaymentFailed(OrderEntity order, String reason) {
        if (order.getStatus() == OrderStatus.AWAITING_PAYMENT) {
            transition(order, OrderStatus.ABANDONED, Actor.SYSTEM, null, "gateway", reason);
        }
    }

    /**
     * The discount a coupon actually took off this quote, from the priced line.
     *
     * <p>Read back off the quote rather than recomputed. The engine has already decided
     * the number, rounded it, and folded the residual — recomputing it here would be a
     * second arithmetic that agrees until it does not.
     */
    private static long discountMinorOf(Quote quote) {
        return quote.lines().stream()
                .filter(l -> l.code() == LineCode.COUPON_DISCOUNT)
                .mapToLong(l -> l.amount().minor())
                .findFirst()
                .orElse(0L);
    }

    /** The rate that discount represents, for the frozen redemption record. */
    private static int discountBpsOf(Quote quote) {
        long discount = Math.abs(discountMinorOf(quote));
        long subtotal = quote.subtotal().minor();
        if (subtotal <= 0 || discount <= 0) {
            return 0;
        }
        return (int) Math.round((discount * 10_000d) / subtotal);
    }

    /** Customer submits their EA sign-in on a comfort-trade order. */
    @Transactional
    public OrderEntity submitCredentials(OrderEntity order,
                                         CredentialDtos.SubmitCredentialsRequest request,
                                         Long actorId) {
        if (!order.requiresCredentials()) {
            throw new ApiExceptions.BadRequestException(
                    "This order is fulfilled through the transfer market and needs no sign-in.");
        }
        /*
         * AWAITING_PAYMENT is accepted because the sign-in is now collected at checkout,
         * alongside the rest of the form, rather than after the money arrives.
         */
        if (order.getStatus() != OrderStatus.AWAITING_PAYMENT
                && order.getStatus() != OrderStatus.CREDENTIALS_PENDING
                && order.getStatus() != OrderStatus.ON_HOLD) {
            throw new ApiExceptions.ConflictException("not_awaiting_credentials",
                    "This order is not waiting for sign-in details.");
        }

        vaultService.store(order.getId(), request);
        if (request.platformHandle() != null && !request.platformHandle().isBlank()) {
            order.setEaPlatformHandle(request.platformHandle());
        }

        /*
         * An unpaid order is stored and left exactly where it was.
         *
         * This is the whole safety of collecting early. Transitioning here would put an
         * order a trader can pick up in the fulfilment queue before any money had
         * arrived; the state machine would refuse the jump anyway -- AWAITING_PAYMENT
         * goes only to PAID or ABANDONED -- so attempting it would throw rather than
         * quietly succeed, but not attempting it is the honest version. `markPaid` reads
         * the vault and queues the order the moment payment lands.
         */
        if (order.getStatus() == OrderStatus.AWAITING_PAYMENT) {
            log.info("Sign-in stored for unpaid order {}; queued once payment lands",
                    order.getPublicRef());
            return order;
        }

        return transition(order, OrderStatus.READY_FOR_DELIVERY, Actor.CUSTOMER, actorId,
                order.getGuestEmail(), "Sign-in details received");
    }

    // ------------------------------------------------------------------ lookup

    /** Customer-facing: ownership is part of the query, never an if-statement after it. */
    @Transactional(readOnly = true)
    public OrderEntity requireOwned(String publicRef, Long accountId) {
        return orders.findByPublicRefAndAccountId(publicRef, accountId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such order."));
    }

    /** Guest lookup: the reference alone is not a credential. */
    @Transactional(readOnly = true)
    public OrderEntity requireGuest(String publicRef, String email) {
        return orders.findByPublicRefAndGuestEmail(
                        publicRef.trim().toUpperCase(Locale.ROOT), email.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new ApiExceptions.NotFoundException(
                        "We could not find an order with that reference and email."));
    }

    /**
     * Staff only, by primary key. Used where the caller already holds a row that
     * references an order -- a manual payment claim, say -- and has no public reference
     * to look it up by.
     */
    @Transactional(readOnly = true)
    public OrderEntity requireById(Long orderId) {
        return orders.findById(orderId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such order."));
    }

    /** Staff only. Never reachable from a customer-facing path. */
    @Transactional(readOnly = true)
    public OrderEntity requireAny(String publicRef) {
        return orders.findByPublicRef(publicRef)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such order."));
    }

    @Transactional(readOnly = true)
    public List<OrderEventEntity> timeline(Long orderId) {
        return events.findByOrderIdOrderByCreatedAtAsc(orderId);
    }

    // ----------------------------------------------------------------- helpers

    private void record(OrderEntity order, OrderStatus from, OrderStatus to, Actor actor,
                        Long actorId, String actorLabel, String reason) {
        events.save(new OrderEventEntity(order.getId(), from, to, actor, actorId, actorLabel, reason));
    }

    private OrderNotification notificationFor(OrderEntity order) {
        return new OrderNotification(
                order.getPublicRef(),
                order.getStatus().name(),
                describe(order),
                order.total().format(),
                order.getGuestEmail(),
                order.getDiscordUsername(),
                order.getDeliveryMethod().name(),
                props.publicUrl() + "/admin/orders/" + order.getPublicRef(),
                coachingSummary(order));
    }

    /**
     * Refuses a coaching order that is missing what the coach needs, and stores the rest.
     *
     * <p>Checked here rather than by annotation because the same request places coin and
     * boosting orders, where none of it applies. Runs before the order is saved, so a
     * refusal leaves nothing behind and the quote can still be used.
     */
    private static void applyCoachingDetails(OrderEntity order, OrderDtos.CreateOrderRequest request) {
        if (request.eaPlatformHandle() == null || request.eaPlatformHandle().isBlank()) {
            throw new ApiExceptions.BadRequestException("coaching_handle_required",
                    "Enter your EA FC ID, PSN or Xbox ID so your coach can find you.");
        }
        if (request.coachingPlatform() == null || request.coachingPlatform().isBlank()) {
            throw new ApiExceptions.BadRequestException("coaching_platform_required",
                    "Choose the platform you play on.");
        }
        order.setEaPlatformHandle(request.eaPlatformHandle().trim());
        order.setCoachingPlatform(Platform.valueOf(request.coachingPlatform()));
        order.setCoachingRank(blankToNull(request.currentRank()));
        order.setCoachingFocus(blankToNull(request.improvementFocus()));
    }

    /**
     * Refuses a boosting order that does not say where it is to be played, and stores it.
     *
     * <p>A boosting tier costs the same on every platform, so the platform is not part of
     * the price and does not travel on the signed quote -- it arrives here, with the rest
     * of what the customer typed, and is written straight onto the order. Nothing about
     * the total can change as a result, which is why taking it from the request rather
     * than from the quote is safe.
     *
     * <p>The launcher question only exists on PC. Asking a PlayStation customer which
     * launcher they use would be nonsense, so it is required exactly when it applies.
     *
     * <p>Package-private rather than private so the rules can be tested on their own:
     * reaching them through {@code create} would need a priced quote, a payment gateway
     * and a database for a check that touches none of them.
     */
    static void applyBoostingDetails(OrderEntity order, OrderDtos.CreateOrderRequest request) {
        if (request.boostPlatform() == null || request.boostPlatform().isBlank()) {
            throw new ApiExceptions.BadRequestException("boost_platform_required",
                    "Choose the platform this account plays on.");
        }
        Platform platform = Platform.valueOf(request.boostPlatform());
        PcLauncher launcher = null;
        if (platform == Platform.PC) {
            if (request.pcLauncher() == null || request.pcLauncher().isBlank()) {
                throw new ApiExceptions.BadRequestException("pc_launcher_required",
                        "Choose the launcher you sign in to FC through.");
            }
            launcher = PcLauncher.valueOf(request.pcLauncher());
        }
        order.setPlatform(platform);
        order.setPcLauncher(launcher);

        /*
         * Named in the label as well as stored in the column.
         *
         * A coin order already reads "Safe Trading Service -- 3M (PC)" everywhere it is
         * quoted, because its platform is part of the priced line. A boosting order's is
         * not, so without this the Discord alert, the operator email, the tracking page
         * and the customer's own order list would all name a tier and no platform -- and
         * the booster would be back to asking on Discord, which is the thing this
         * replaces.
         */
        String label = order.getServiceLabel();
        if (label != null && !label.isBlank()) {
            order.setServiceLabel(label + " (" + platformSummary(platform, launcher) + ")");
        }
    }

    /** "PlayStation", or "PC · Steam" where the launcher is known. */
    private static String platformSummary(Platform platform, PcLauncher launcher) {
        return launcher == null
                ? platform.displayName()
                : platform.displayName() + " · " + launcher.displayName();
    }

    /**
     * One line an operator or coach can read at a glance, or null for any other service.
     *
     * <p>Built from what the customer typed, so it goes through Discord's mention guard
     * like every other customer-supplied field -- see DiscordNotifier.
     */
    public static String coachingSummary(OrderEntity order) {
        if (order.getSku() != Sku.COACHING) {
            return null;
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (order.getCoachingPlatform() != null) {
            parts.add(order.getCoachingPlatform().displayName());
        }
        if (order.getEaPlatformHandle() != null && !order.getEaPlatformHandle().isBlank()) {
            parts.add("ID " + order.getEaPlatformHandle());
        }
        if (order.getCoachingRank() != null) {
            parts.add(order.getCoachingRank());
        }
        if (order.getCoachingFocus() != null) {
            parts.add("Wants to work on: " + order.getCoachingFocus());
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    /**
     * The description that appears on the invoice, in the gateway's line item, in the
     * admin queue and in the operator's notification. It says "trading service"
     * everywhere, because that is what is sold — and because the wording has to match the
     * Terms of Service and the merchant-category declaration exactly.
     *
     * <p><b>It reads the label stored on the order; it does not rebuild one.</b> This
     * method used to compose its own from the raw variant, which meant the customer's
     * invoice said "Rivals Boosting — Division 1 to Elite" while the operator queue, the
     * Razorpay line item and the notification all said "Rivals Boosting —
     * DIV_1_TO_ELITE". Two derivations of one fact, and the ugly one was what everybody
     * except the customer saw. The pricing engine now computes the line once, the order
     * records it, and this returns what was recorded.
     *
     * <p>The fallback below only fires for rows written before that column existed and
     * missed by the backfill. It is deliberately the old behaviour rather than a lookup:
     * an order's name should not change because the catalogue was retitled afterwards.
     */
    public static String describe(OrderEntity order) {
        String stored = order.getServiceLabel();
        if (stored != null && !stored.isBlank()) {
            return stored;
        }
        return switch (order.getSku()) {
            case TRADING_SERVICE -> "Buy Coins — "
                    + CoinAmount.describe(order.getQuantity())
                    + (order.getPlatform() == null ? "" : " (" + order.getPlatform().displayName() + ")");
            case BOOST_CHAMPS -> "Champs Boosting — " + order.getVariant();
            case BOOST_RIVALS -> "Rivals Boosting — " + order.getVariant();
            case COACHING -> "FUT Classes — " + order.getVariant();
            case CARDS -> "Player Cards — " + order.getVariant();
        };
    }

    /**
     * The line item the payment processor stores, which is not what the customer sees.
     *
     * <p>Same order, same detail -- the amount, the platform, the tier -- with the SKU
     * named the way {@link Sku#gatewayName()} explains. The detail is taken from the
     * frozen label rather than recomposed, so the two descriptions can never drift into
     * describing different orders.
     */
    static String gatewayDescription(OrderEntity order) {
        String label = describe(order);
        int dash = label.indexOf(" — ");
        return dash < 0
                ? order.getSku().gatewayName()
                : order.getSku().gatewayName() + label.substring(dash);
    }

    public static Money totalOf(OrderEntity order) {
        return order.total();
    }

    /** Empty and whitespace-only both mean "not supplied". */
    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
