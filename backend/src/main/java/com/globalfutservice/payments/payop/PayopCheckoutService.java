package com.globalfutservice.payments.payop;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.domain.pricing.LineCode;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Paying an order through Payop, from the customer's side: what each method would cost,
 * and starting the payment.
 *
 * <p>Everything about the price is worked out here, on the server, every time. The browser
 * says which method it wants and what total it showed; the total is recomputed, and a
 * difference is refused rather than charged. The amount is the order's price without the
 * flat card fee -- coupons and points are already in it -- plus the chosen method's own fee,
 * grossed up so the business receives the price in full. Nothing the browser sends ever
 * becomes a price.
 *
 * <p>One active attempt per order, held by a unique index. Asking again for the same method
 * hands back the invoice already made, so a double click or a retry never creates a second
 * charge. Choosing another method replaces the attempt: Payop cannot cancel an invoice, so
 * the old one stays payable until its 24 hours are up, and a payment on it is still taken --
 * but only one payment is ever applied to an order (see {@link PayopCallbackService}). Going
 * back to that method at the same price reopens the old invoice rather than making another.
 *
 * <p>New invoices are limited per order and per account ({@code gfs.payop.invoices-per-*}),
 * counted from this table rather than by the caller's address. Reopening or handing back an
 * invoice already made is never limited.
 *
 * <p>Nothing here marks an order paid. Only a confirmed IPN does.
 */
@Service
public class PayopCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(PayopCheckoutService.class);

    /** What the fee is called wherever a customer sees it. */
    public static final String FEE_LABEL = "Payment processing fee";
    /** An attempt still "creating" after this was interrupted mid-request. */
    static final Duration CREATING_TIMEOUT = Duration.ofMinutes(2);
    private static final Set<String> LANGUAGES = Set.of("en", "es", "fr");
    private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());
    private static final List<PayopInvoiceEntity.Status> ACTIVE =
            List.of(PayopInvoiceEntity.Status.CREATING, PayopInvoiceEntity.Status.OPEN);

    /** One method the customer can pick, priced for this order. */
    public record MethodOption(long methodId, String name, String type, long feeMinor, long totalMinor) {
    }

    /**
     * What the customer is offered. {@code unavailable} names why there is nothing to pick
     * ("NO_RATE", "PAYOP_UNAVAILABLE"), or is null.
     */
    public record Options(Currency currency, long netMinor, List<MethodOption> methods, String unavailable) {
    }

    /** A payment ready for the customer: where to send them. */
    public record Started(String redirectUrl, String invoiceId, long totalMinor, Instant payableUntil) {
    }

    /** An invoice the customer can still pay at Payop, and the page to pay it on. */
    public record PayableInvoice(String invoiceId, String methodName, long totalMinor, Currency currency,
                                 Instant payableUntil, String url) {
    }

    /** What a return page may show: statuses and the amount, nothing that moves anything. */
    public record ReturnStatus(String payment, String order, long totalMinor, Currency currency, String methodName) {
    }

    private final PayopInvoiceRepository invoices;
    private final OrderRepository orders;
    private final PayopMethodsService methods;
    private final FxRateService fx;
    private final PayopClient client;
    private final AppProperties props;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PayopCheckoutService(PayopInvoiceRepository invoices, OrderRepository orders, PayopMethodsService methods,
                                FxRateService fx, PayopClient client, AppProperties props, ObjectMapper mapper,
                                PlatformTransactionManager transactions, Clock clock) {
        this.invoices = invoices;
        this.orders = orders;
        this.methods = methods;
        this.fx = fx;
        this.client = client;
        this.props = props;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /* ------------------------------------------------------------------- offer --- */

    /** Every method for {@code country}, each with its fee and the total the customer would pay. */
    public Options options(OrderEntity order, String country) {
        requirePayable(order);
        String iso = country(country);
        long net = netMinor(order);
        Currency currency = order.getCurrency();
        Optional<FxRateService.RateUsed> rate = fx.eurTo(currency);
        if (rate.isEmpty()) {
            log.warn("No EUR rate for {}: Payop not offered on order {}", currency, order.getPublicRef());
            return new Options(currency, net, List.of(), "NO_RATE");
        }
        PayopMethodsService.Availability available = methods.forCountry(iso);
        if (available.unavailable()) {
            return new Options(currency, net, List.of(), "PAYOP_UNAVAILABLE");
        }
        List<MethodOption> out = new ArrayList<>();
        for (PayopMethodsService.Offered m : available.methods()) {
            PayopFeeCalculator.FeeQuote fee = price(net, currency, m.fee(), rate.get());
            out.add(new MethodOption(m.fee().getMethodId(), m.fee().getName(), m.fee().getMethodType(),
                    fee.feeMinor(), fee.totalMinor()));
        }
        return new Options(currency, net, out, null);
    }

    /* ------------------------------------------------------------------- start --- */

    /**
     * Starts paying {@code order} with {@code methodId}. {@code expectedTotalMinor} is the
     * total the customer was shown; if the price has moved since, nothing is created and
     * they are asked to look again.
     */
    public Started start(OrderEntity order, long methodId, String country, long expectedTotalMinor,
                         String language) {
        requirePayable(order);
        String iso = country(country);
        String lang = language == null ? "en" : language.trim().toLowerCase(Locale.ROOT);
        if (!LANGUAGES.contains(lang)) {
            lang = "en";
        }
        expireStale();

        long net = netMinor(order);
        Currency currency = order.getCurrency();
        FxRateService.RateUsed rate = fx.eurTo(currency).orElseThrow(() -> new ApiExceptions.ConflictException(
                "payop_unavailable", "This payment option is not available for your order's currency right now."));
        PayopMethodsService.Offered method = methods.find(methodId, iso).orElseThrow(() ->
                new ApiExceptions.ConflictException("method_unavailable",
                        "That payment method is not available for the country you chose."));
        PayopFeeCalculator.FeeQuote fee = price(net, currency, method.fee(), rate);
        if (fee.totalMinor() != expectedTotalMinor) {
            throw new ApiExceptions.ConflictException("price_changed",
                    "The total for this payment method has changed. Check the new total and try again.");
        }
        String amount = PayopFeeCalculator.amount(fee.totalMinor(), currency);

        // A retry for the same method and total gets the invoice already made.
        Optional<PayopInvoiceEntity> active = invoices.findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(
                order.getId(), ACTIVE);
        if (active.isPresent()) {
            PayopInvoiceEntity a = active.get();
            if (a.getStatus() == PayopInvoiceEntity.Status.CREATING) {
                throw new ApiExceptions.ConflictException("payment_starting",
                        "Your payment is already being set up. Wait a moment and try again.");
            }
            if (a.getMethodId() == methodId && a.getTotalMinor() == fee.totalMinor()
                    && a.getCurrency() == currency) {
                return new Started(redirectUrl(lang, a.getInvoiceId()), a.getInvoiceId(), a.getTotalMinor(),
                        a.getExpiresAt());
            }
        }

        Instant now = clock.instant();

        // Back to a method whose invoice Payop can still take, at the same price: that one.
        Optional<PayopInvoiceEntity> earlier = invoices.findByOrderIdOrderByCreatedAtDesc(order.getId()).stream()
                .filter(a -> a.isReplacedButPayable(now) && a.getMethodId() == methodId
                        && a.getTotalMinor() == fee.totalMinor() && a.getCurrency() == currency)
                .findFirst();
        if (earlier.isPresent()) {
            PayopInvoiceEntity reopened;
            try {
                reopened = tx.execute(status -> {
                    replace(active, now);
                    PayopInvoiceEntity a = invoices.findById(earlier.get().getId()).orElseThrow();
                    a.reopened(now);
                    return invoices.saveAndFlush(a);
                });
            } catch (DataIntegrityViolationException e) {
                throw new ApiExceptions.ConflictException("payment_starting",
                        "Your payment is already being set up. Wait a moment and try again.");
            }
            log.info("Payop invoice {} reopened for order {}: method {}", reopened.getInvoiceId(),
                    order.getPublicRef(), methodId);
            return new Started(redirectUrl(lang, reopened.getInvoiceId()), reopened.getInvoiceId(),
                    reopened.getTotalMinor(), reopened.getExpiresAt());
        }

        requireWithinLimits(order, now);

        PayopInvoiceEntity attempt;
        try {
            attempt = tx.execute(status -> {
                replace(active, now);
                return invoices.saveAndFlush(new PayopInvoiceEntity(order.getId(), UUID.randomUUID(), method.fee(),
                        rate, currency, fee, amount, iso, now, now.plus(props.payop().invoiceLifetime())));
            });
        } catch (DataIntegrityViolationException e) {
            // Another request for this order won the unique index.
            throw new ApiExceptions.ConflictException("payment_starting",
                    "Your payment is already being set up. Wait a moment and try again.");
        }

        String invoiceId;
        try {
            invoiceId = client.createInvoice(new PayopClient.InvoiceRequest(order.getPublicRef(), amount, currency,
                    "Order " + order.getPublicRef(), order.getGuestEmail(), methodId, lang,
                    returnUrl(order, false), returnUrl(order, true), attempt.getAttemptId()));
        } catch (PayopClient.PayopException e) {
            final Long id = attempt.getId();
            tx.executeWithoutResult(status -> invoices.findById(id).ifPresent(a -> {
                a.failed(null, "CREATE_" + e.code(), clock.instant());
                invoices.save(a);
            }));
            log.warn("Payop invoice for order {} not created: {}", order.getPublicRef(), e.code());
            if (e.code() == PayopClient.ErrorCode.METHOD_NOT_ENABLED) {
                methods.refresh();
                throw new ApiExceptions.ConflictException("method_unavailable",
                        "That payment method is not available right now. Choose another.");
            }
            throw new ApiExceptions.UpstreamException(
                    "We could not start the payment with our payment provider. Try again in a few minutes.", e);
        }

        final Long id = attempt.getId();
        final String opened = invoiceId;
        PayopInvoiceEntity saved = tx.execute(status -> {
            PayopInvoiceEntity a = invoices.findById(id).orElseThrow();
            a.opened(opened, clock.instant());
            return invoices.save(a);
        });
        log.info("Payop invoice {} opened for order {}: method {}, {} {}", invoiceId, order.getPublicRef(),
                methodId, amount, currency);
        return new Started(redirectUrl(lang, invoiceId), invoiceId, saved.getTotalMinor(), saved.getExpiresAt());
    }

    /** Closes the order's active attempt, if any: the customer chose another method. */
    private void replace(Optional<PayopInvoiceEntity> active, Instant now) {
        active.ifPresent(old -> invoices.findById(old.getId()).ifPresent(o -> {
            if (o.isActive()) {
                o.expired(PayopInvoiceEntity.REPLACED, now);
                invoices.saveAndFlush(o);
            }
        }));
    }

    /**
     * A new invoice is within both limits, or the customer is told when the next can be
     * opened. Counted by order and by account, never by address: a caller's address is
     * theirs to choose.
     */
    private void requireWithinLimits(OrderEntity order, Instant now) {
        AppProperties.Payop p = props.payop();
        Instant orderSince = now.minus(p.invoicesPerOrderWindow());
        if (invoices.countByOrderIdAndCreatedAtAfter(order.getId(), orderSince) >= p.invoicesPerOrder()) {
            Instant retryAt = invoices.firstForOrderSince(order.getId(), orderSince)
                    .orElse(now).plus(p.invoicesPerOrderWindow());
            log.info("Payop invoice limit reached for order {}", order.getPublicRef());
            throw new ApiExceptions.TooManyRequestsException("payment_attempts_order",
                    "This payment has been started several times. Complete one you have already opened, "
                            + "or try again later.", retryAt);
        }
        if (order.getAccountId() == null) {
            return;
        }
        Instant accountSince = now.minus(p.invoicesPerAccountWindow());
        if (invoices.countForAccountSince(order.getAccountId(), accountSince) >= p.invoicesPerAccount()) {
            Instant retryAt = invoices.firstForAccountSince(order.getAccountId(), accountSince)
                    .orElse(now).plus(p.invoicesPerAccountWindow());
            log.info("Payop invoice limit reached for the account on order {}", order.getPublicRef());
            throw new ApiExceptions.TooManyRequestsException("payment_attempts_account",
                    "Several payments have been started from your account recently. Complete one you have "
                            + "already opened, or try again later.", retryAt);
        }
    }

    /* ------------------------------------------------------------------ return --- */

    /**
     * What the page Payop sends the customer back to may show. Read-only: arriving there
     * proves nothing, so nothing is marked paid here, whatever the URL says.
     */
    public ReturnStatus returnStatus(String orderRef, String invoiceId) {
        if (!props.payop().enabled()) {
            throw new ApiExceptions.NotFoundException("No such payment.");
        }
        String ref = orderRef == null ? "" : orderRef.trim().toUpperCase(Locale.ROOT);
        PayopInvoiceEntity a = invoices.findByInvoiceId(invoiceId == null ? "" : invoiceId.trim())
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such payment."));
        OrderEntity order = orders.findById(a.getOrderId())
                .filter(o -> o.getPublicRef().equals(ref))
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such payment."));
        String payment = switch (a.getStatus()) {
            case PAID -> "PAID";
            case FAILED -> "FAILED";
            case REVIEW, DUPLICATE -> "REVIEW";
            case CREATING, OPEN, EXPIRED -> order.getStatus() == OrderStatus.AWAITING_PAYMENT ? "PENDING" : "CLOSED";
        };
        return new ReturnStatus(payment, order.getStatus().name(), a.getTotalMinor(), a.getCurrency(),
                a.getMethodName());
    }

    /* ------------------------------------------------------------- the rest --- */

    /**
     * The invoice to send the customer back to, if Payop can still take one for this order:
     * the active attempt, or else the latest the customer moved away from.
     */
    public Optional<PayableInvoice> payableInvoice(OrderEntity order, String language) {
        String lang = language == null ? "en" : language.trim().toLowerCase(Locale.ROOT);
        if (!LANGUAGES.contains(lang)) {
            lang = "en";
        }
        Instant now = clock.instant();
        List<PayopInvoiceEntity> all = invoices.findByOrderIdOrderByCreatedAtDesc(order.getId());
        Optional<PayopInvoiceEntity> found = all.stream()
                .filter(a -> a.getStatus() == PayopInvoiceEntity.Status.OPEN && a.getInvoiceId() != null
                        && a.getExpiresAt().isAfter(now))
                .findFirst()
                .or(() -> all.stream().filter(a -> a.isReplacedButPayable(now)).findFirst());
        final String l = lang;
        return found.map(a -> new PayableInvoice(a.getInvoiceId(), a.getMethodName(), a.getTotalMinor(),
                a.getCurrency(), a.getExpiresAt(), redirectUrl(l, a.getInvoiceId())));
    }

    /**
     * Until when manual payment claims for this order wait: while a Payop invoice for it can
     * still be paid, a second way of paying invites paying twice.
     */
    public Optional<Instant> claimsBlockedUntil(Long orderId) {
        return invoices.payableUntil(orderId, clock.instant(), PayopInvoiceEntity.Status.CREATING);
    }

    /** Closes attempts whose 24 hours are up, and ones whose creation was interrupted. */
    @Scheduled(cron = "0 5 * * * *")
    public void expireStale() {
        Instant now = clock.instant();
        tx.executeWithoutResult(status -> {
            for (PayopInvoiceEntity a : invoices.findByStatusAndExpiresAtLessThanEqual(PayopInvoiceEntity.Status.OPEN, now)) {
                a.expired("LIFETIME", now);
            }
            for (PayopInvoiceEntity a : invoices.findByStatusAndUpdatedAtBefore(PayopInvoiceEntity.Status.CREATING,
                    now.minus(CREATING_TIMEOUT))) {
                a.failed(null, "CREATE_INTERRUPTED", now);
            }
        });
    }

    /**
     * The order's price without the flat card fee: the quote's total less its GATEWAY_FEE
     * line. The quote was verified when the order was placed and is frozen on it, so this
     * is the server's own number, with coupons and points already taken off.
     */
    long netMinor(OrderEntity order) {
        long gateway = 0;
        try {
            for (JsonNode line : mapper.readTree(order.getPriceBreakdown()).path("lines")) {
                if (LineCode.GATEWAY_FEE.name().equals(line.path("code").asText())) {
                    gateway += line.path("amountMinor").asLong();
                }
            }
        } catch (Exception e) {
            throw new ApiExceptions.ConflictException("payop_unavailable",
                    "This payment option is not available for this order.");
        }
        long net = order.getTotalMinor() - gateway;
        if (net <= 0) {
            throw new ApiExceptions.ConflictException("payop_unavailable",
                    "This payment option is not available for this order.");
        }
        return net;
    }

    private static PayopFeeCalculator.FeeQuote price(long net, Currency currency, PayopFeeMethodEntity fee,
                                                     FxRateService.RateUsed rate) {
        return PayopFeeCalculator.quote(net, currency, fee.getFixedEur(), fee.getPercent(), rate.rate());
    }

    private void requirePayable(OrderEntity order) {
        if (!props.payop().enabled()) {
            throw new ApiExceptions.NotFoundException("This payment option is not available.");
        }
        if (order.getCurrency() == Currency.INR) {
            throw new ApiExceptions.BadRequestException("payop_not_for_inr",
                    "This payment option is for orders in other currencies.");
        }
        if (order.getStatus() != OrderStatus.AWAITING_PAYMENT) {
            throw new ApiExceptions.ConflictException("payment_not_pending",
                    "This order is not waiting for payment. Contact support if you have paid twice.");
        }
    }

    private static String country(String country) {
        String iso = country == null ? "" : country.trim().toUpperCase(Locale.ROOT);
        if (!ISO_COUNTRIES.contains(iso)) {
            throw new ApiExceptions.BadRequestException("unknown_country", "Choose your country from the list.");
        }
        return iso;
    }

    private String redirectUrl(String lang, String invoiceId) {
        return props.payop().checkoutUrl() + "/" + lang + "/payment/invoice-preprocessing/" + invoiceId;
    }

    /** Payop fills in {{invoiceId}}; the page shows what the server says, nothing more. */
    private String returnUrl(OrderEntity order, boolean failed) {
        return props.publicUrl() + "/payment/payop/return?ref=" + order.getPublicRef() + "&invoice={{invoiceId}}"
                + (failed ? "&result=failed" : "");
    }
}
