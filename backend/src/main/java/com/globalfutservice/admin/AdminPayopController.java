package com.globalfutservice.admin;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.payments.WebhookEventRepository;
import com.globalfutservice.payments.payop.FxRateEntity;
import com.globalfutservice.payments.payop.FxRateService;
import com.globalfutservice.payments.payop.PayopCallbackService;
import com.globalfutservice.payments.payop.PayopFeeAuditEntity;
import com.globalfutservice.payments.payop.PayopFeeMethodEntity;
import com.globalfutservice.payments.payop.PayopFeeTableService;
import com.globalfutservice.payments.payop.PayopInvoiceEntity;
import com.globalfutservice.payments.payop.PayopInvoiceRepository;
import com.globalfutservice.payments.payop.PayopMethodsService;
import com.globalfutservice.payments.payop.PayopReconciliation;
import com.globalfutservice.payments.payop.PayopStartupCheck;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Payop administration: the fee table, the exchange rates, and the payments that need a
 * person.
 *
 * <p>Seeing all of it is an operator's job; changing what customers are charged -- a fee, a
 * rate, a whole new pricing sheet -- is ADMIN only, like the rate card. So is accepting a
 * payment by hand that Payop's API could not confirm. Checking a payment with Payop is
 * open to operators: it only asks Payop, and pays an order only if Payop's answer passes
 * every check an IPN's would.
 */
@RestController
@RequestMapping("/api/v1/admin/payop")
@Tag(name = "Admin — Payop", description = "Payop fees, exchange rates and payments needing review")
public class AdminPayopController {

    private static final Logger log = LoggerFactory.getLogger(AdminPayopController.class);
    private static final Set<PayopInvoiceEntity.Status> ATTENTION =
            Set.of(PayopInvoiceEntity.Status.REVIEW, PayopInvoiceEntity.Status.DUPLICATE);

    public record OverviewDto(boolean enabled, boolean configured, String jwtExpiresAt, String merchantPaysNote,
                              int methods, int activeMethods, List<RateDto> rates, long needsAttention) {
    }

    public record FeeDto(long methodId, String name, String type, String region, BigDecimal fixedEur,
                         BigDecimal percent, List<String> countries, List<String> currencies, boolean active,
                         int version, Instant updatedAt, Long updatedBy) {
        static FeeDto of(PayopFeeMethodEntity m) {
            return new FeeDto(m.getMethodId(), m.getName(), m.getMethodType(), m.getRegion(), m.getFixedEur(),
                    m.getPercent(), m.countryList(), m.currencyList(), m.isActive(), m.getVersion(), m.getUpdatedAt(),
                    m.getUpdatedBy());
        }
    }

    public record FeeEditRequest(@NotNull BigDecimal fixedEur, @NotNull BigDecimal percent,
                                 @NotNull List<String> countries, @NotNull List<String> currencies, boolean active) {
    }

    public record AuditDto(long methodId, int version, String action, String before, String after, Long actorId,
                           Instant at) {
        static AuditDto of(PayopFeeAuditEntity a) {
            return new AuditDto(a.getMethodId(), a.getVersion(), a.getAction(), a.getBeforeJson(), a.getAfterJson(),
                    a.getActorId(), a.getAt());
        }
    }

    /** {@code current} is what a fee would use right now: the source and date say how. */
    public record RateDto(String currency, BigDecimal rate, String source, LocalDate date, boolean current) {
    }

    public record AdminRateRequest(@NotBlank String currency, @NotNull BigDecimal rate, @NotNull LocalDate date) {
    }

    public record InvoiceDto(long id, String orderRef, String invoiceId, String status, String reason,
                             long methodId, String methodName, String currency, long netMinor, long feeMinor,
                             long totalMinor, String totalFormatted, String amountSent, String country, String txid,
                             Instant createdAt, Instant expiresAt, Instant paidAt, Instant updatedAt) {
    }

    public record RejectedIpnDto(String payload, Instant receivedAt) {
    }

    public record VerifyRequest(@NotBlank String txid) {
    }

    public record AcceptRequest(@NotBlank String txid, @NotBlank String note) {
    }

    /** One invoice re-checked for an order, as it stood before and what came of it. */
    public record RecheckedDto(String invoiceId, String statusBefore, String outcome) {
    }

    /** The order's status after the re-check, and each invoice looked at. */
    public record RecheckDto(String order, String orderStatus, List<RecheckedDto> invoices) {
    }

    public record OutcomeDto(String outcome) {
    }

    private final PayopFeeTableService fees;
    private final FxRateService fx;
    private final PayopInvoiceRepository invoices;
    private final OrderRepository orders;
    private final PayopCallbackService callbacks;
    private final PayopMethodsService methods;
    private final WebhookEventRepository webhooks;
    private final PayopReconciliation reconciliation;
    private final AppProperties props;

    public AdminPayopController(PayopFeeTableService fees, FxRateService fx, PayopInvoiceRepository invoices,
                                OrderRepository orders, PayopCallbackService callbacks, PayopMethodsService methods,
                                WebhookEventRepository webhooks, PayopReconciliation reconciliation,
                                AppProperties props) {
        this.fees = fees;
        this.fx = fx;
        this.invoices = invoices;
        this.orders = orders;
        this.callbacks = callbacks;
        this.methods = methods;
        this.webhooks = webhooks;
        this.reconciliation = reconciliation;
        this.props = props;
    }

    /* ---------------------------------------------------------------- overview --- */

    @GetMapping
    @Operation(summary = "Whether Payop is on, the fee table at a glance, the rates in use, and what needs a person")
    public ResponseEntity<OverviewDto> overview() {
        List<PayopFeeMethodEntity> table = fees.list();
        AppProperties.Payop p = props.payop();
        return noStore(new OverviewDto(p.enabled(), p.configured(), p.jwtExpiresAt(), PayopStartupCheck.MERCHANT_PAYS,
                table.size(), (int) table.stream().filter(PayopFeeMethodEntity::isActive).count(), currentRates(),
                invoices.countByStatusIn(ATTENTION)));
    }

    /* -------------------------------------------------------------------- fees --- */

    @GetMapping("/fees")
    @Operation(summary = "The fee table")
    public ResponseEntity<List<FeeDto>> fees() {
        return noStore(fees.list().stream().map(FeeDto::of).toList());
    }

    @PutMapping("/fees/{methodId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Correct one method's fee, countries, currencies or on/off; versioned and audited")
    public ResponseEntity<FeeDto> editFee(@PathVariable long methodId, @Valid @RequestBody FeeEditRequest request,
                                          @CurrentAccount AccountPrincipal admin) {
        PayopFeeMethodEntity row = fees.update(methodId, new PayopFeeTableService.Edit(request.fixedEur(),
                request.percent(), request.countries(), request.currencies(), request.active()), admin.id());
        methods.refresh();
        return noStore(FeeDto.of(row));
    }

    @PostMapping(value = "/fees/import", consumes = "multipart/form-data")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Load the client's Payop pricing sheet (.xlsx); all or nothing",
            description = "`expected` is how many methods the sheet should hold; a different count imports nothing.")
    public ResponseEntity<PayopFeeTableService.ImportResult> importSheet(
            @RequestParam("file") MultipartFile file, @RequestParam(value = "expected", required = false) Integer expected,
            @CurrentAccount AccountPrincipal admin) throws IOException {
        log.info("Admin {} is importing a Payop pricing sheet", admin.id());
        PayopFeeTableService.ImportResult result = fees.importSheet(file.getBytes(), expected, admin.id());
        methods.refresh();
        return noStore(result);
    }

    @GetMapping("/fees/audit")
    @Operation(summary = "Changes to the fee table, newest first; one method's, or the latest 200")
    public ResponseEntity<List<AuditDto>> audit(@RequestParam(required = false) Long methodId) {
        return noStore(fees.history(methodId).stream().map(AuditDto::of).toList());
    }

    /* ------------------------------------------------------------- exchange --- */

    @GetMapping("/fx")
    @Operation(summary = "The rate each currency's fee would use now, and the latest rates held")
    public ResponseEntity<Map<String, List<RateDto>>> rates() {
        List<RateDto> recent = fx.recent().stream().map(r -> new RateDto(r.getQuote(), r.getRate(), r.getSource(),
                r.getRateDate(), false)).toList();
        return noStore(Map.of("current", currentRates(), "recent", recent));
    }

    @PostMapping("/fx")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Enter a rate by hand: used when the ECB's is missing or more than five days old")
    public ResponseEntity<RateDto> enterRate(@Valid @RequestBody AdminRateRequest request,
                                             @CurrentAccount AccountPrincipal admin) {
        Currency currency = currency(request.currency());
        FxRateEntity saved;
        try {
            saved = fx.enterAdminRate(currency, request.rate(), request.date(), admin.id());
        } catch (IllegalArgumentException e) {
            throw new ApiExceptions.BadRequestException(e.getMessage());
        }
        log.info("Admin {} entered a EUR→{} rate for {}", admin.id(), currency, request.date());
        return noStore(new RateDto(saved.getQuote(), saved.getRate(), saved.getSource(), saved.getRateDate(), false));
    }

    @PostMapping("/fx/refresh")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Fetch the ECB's rates now")
    public ResponseEntity<Map<String, Integer>> refreshRates() {
        return noStore(Map.of("stored", fx.refreshFromEcb()));
    }

    /* ---------------------------------------------------------------- payments --- */

    @GetMapping("/invoices")
    @Operation(summary = "Payop payments: those needing a person (review, duplicate), or the latest 200")
    public ResponseEntity<List<InvoiceDto>> invoices(@RequestParam(defaultValue = "attention") String show) {
        List<PayopInvoiceEntity> rows = "all".equalsIgnoreCase(show)
                ? invoices.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 200))
                : invoices.findByStatusInOrderByUpdatedAtDesc(ATTENTION, PageRequest.of(0, 200));
        Map<Long, String> refs = orders.findAllById(rows.stream().map(PayopInvoiceEntity::getOrderId).distinct()
                .toList()).stream().collect(Collectors.toMap(OrderEntity::getId, OrderEntity::getPublicRef));
        return noStore(rows.stream().map(a -> new InvoiceDto(a.getId(), refs.get(a.getOrderId()), a.getInvoiceId(),
                a.getStatus().name(), a.getReviewReason(), a.getMethodId(), a.getMethodName(), a.getCurrency().name(),
                a.getNetMinor(), a.getFeeMinor(), a.getTotalMinor(),
                Money.ofMinor(a.getTotalMinor(), a.getCurrency()).format(), a.getAmountSent(), a.getCountry(),
                a.getTxid(), a.getCreatedAt(), a.getExpiresAt(), a.getPaidAt(), a.getUpdatedAt())).toList());
    }

    @GetMapping("/rejected")
    @Operation(summary = "IPNs for our invoices refused because of where they came from",
            description = "Each can be checked with Payop: take its txid to the verify action of its invoice.")
    public ResponseEntity<List<RejectedIpnDto>> rejected() {
        return noStore(webhooks.findTop100ByProviderAndEventTypeOrderByReceivedAtDesc(PayopCallbackService.PROVIDER,
                PayopCallbackService.EVENT_REJECTED).stream()
                .map(e -> new RejectedIpnDto(e.getPayload(), e.getReceivedAt())).toList());
    }

    @PostMapping("/invoices/{id}/verify")
    @Operation(summary = "Ask Payop about a transaction on this invoice and act on the answer",
            description = "Pays the order only if Payop says accepted, for this invoice's exact amount, currency and order.")
    public ResponseEntity<OutcomeDto> verify(@PathVariable long id, @Valid @RequestBody VerifyRequest request,
                                             @CurrentAccount AccountPrincipal operator) {
        PayopInvoiceEntity a = invoices.findById(id).orElseThrow(() -> new ApiExceptions.NotFoundException(
                "No such Payop payment."));
        log.info("Operator {} is checking Payop invoice {} with Payop", operator.id(), a.getInvoiceId());
        try {
            return noStore(new OutcomeDto(callbacks.confirm(a.getId(), request.txid().trim()).name()));
        } catch (IllegalArgumentException e) {
            throw new ApiExceptions.BadRequestException("That is not a Payop transaction ID.");
        }
    }

    @PostMapping("/orders/{ref}/recheck")
    @Operation(summary = "Re-check Payop payment: ask Payop about every unpaid invoice of this order",
            description = """
                    The same check the reconciliation job runs every few minutes, for one order and at
                    once. Pays the order only if Payop reports an invoice paid and its transaction passes
                    every check an IPN's would; a paid invoice Payop names no transaction for goes to
                    review. For an order a lost or refused IPN left unpaid.
                    """)
    public ResponseEntity<RecheckDto> recheck(@PathVariable String ref, @CurrentAccount AccountPrincipal operator) {
        OrderEntity order = orders.findByPublicRef(ref.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such order."));
        log.info("Operator {} is re-checking Payop payment for order {}", operator.id(), order.getPublicRef());
        List<RecheckedDto> checked = reconciliation.recheckOrder(order.getId()).stream()
                .map(c -> new RecheckedDto(c.invoiceId(), c.status(), c.outcome().name())).toList();
        String status = orders.findById(order.getId()).map(o -> o.getStatus().name()).orElse(null);
        return noStore(new RecheckDto(order.getPublicRef(), status, checked));
    }

    @PostMapping("/invoices/{id}/accept")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Accept a payment in review by hand, after checking it in Payop's dashboard",
            description = "For a payment Payop's API could not confirm. Applied once, like any other; never for a duplicate.")
    public ResponseEntity<OutcomeDto> accept(@PathVariable long id, @Valid @RequestBody AcceptRequest request,
                                             @CurrentAccount AccountPrincipal admin) {
        PayopInvoiceEntity a = invoices.findById(id).orElseThrow(() -> new ApiExceptions.NotFoundException(
                "No such Payop payment."));
        if (a.getStatus() != PayopInvoiceEntity.Status.REVIEW) {
            throw new ApiExceptions.ConflictException("not_in_review", "Only a payment in review can be accepted.");
        }
        log.info("Admin {} accepted Payop invoice {} by hand ({})", admin.id(), a.getInvoiceId(),
                request.note().length() > 200 ? request.note().substring(0, 200) : request.note());
        return noStore(new OutcomeDto(callbacks.acceptByHand(a.getId(), request.txid().trim(), admin.id()).name()));
    }

    /* ----------------------------------------------------------------- helpers --- */

    private List<RateDto> currentRates() {
        List<RateDto> out = new ArrayList<>();
        for (Currency c : Arrays.stream(Currency.values()).filter(c -> c != Currency.INR && c != Currency.EUR).toList()) {
            out.add(fx.eurTo(c).map(r -> new RateDto(c.name(), r.rate(), r.source(), r.date(), true))
                    .orElse(new RateDto(c.name(), null, null, null, false)));
        }
        return out;
    }

    private static Currency currency(String code) {
        try {
            return Currency.valueOf(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiExceptions.BadRequestException("Unknown currency " + code);
        }
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
