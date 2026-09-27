package com.globalfutservice.admin;

import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.payments.ManualPaymentMethod;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.payments.RefundEntity;
import com.globalfutservice.payments.RefundService;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Payments: every payment a customer reported, its status, and refunds.
 *
 * <p>Staff read the list, because checking payments is their daily work, and each row
 * shows its own amount as the payments-to-check queue always has. <b>Totals are an
 * admin's</b>: an operator's overview carries counts only. The export and recording a
 * refund are an admin's too: one is a file of money, the other says money left the
 * business. Verifying and rejecting stay on the endpoints they have always used.
 */
@RestController
@RequestMapping("/api/v1/admin/payments")
@Tag(name = "Admin — payments", description = "Payments reported by customers, and refunds")
public class AdminPaymentController {

    private static final Logger log = LoggerFactory.getLogger(AdminPaymentController.class);

    static final int EXPORT_CAP = 10_000;

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(AdminOrderQueries.BUSINESS_ZONE);

    private final AdminPaymentQueries queries;
    private final RefundService refunds;

    public AdminPaymentController(AdminPaymentQueries queries, RefundService refunds) {
        this.queries = queries;
        this.refunds = refunds;
    }

    static AdminPaymentQueries.Filter filter(String status, String method, LocalDate from, LocalDate to, String search) {
        String s = blank(status) || "ALL".equalsIgnoreCase(status) ? null : status.trim().toUpperCase(Locale.ROOT);
        if (s != null && !AdminPaymentQueries.STATUSES.contains(s)) {
            throw new ApiExceptions.BadRequestException("Unknown status filter.");
        }
        String m = blank(method) ? null : method.trim().toUpperCase(Locale.ROOT);
        if (m != null && !AdminPaymentQueries.METHODS.contains(m)) {
            throw new ApiExceptions.BadRequestException("Unknown payment method.");
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw new ApiExceptions.BadRequestException("The date range ends before it starts.");
        }
        String q = blank(search) ? null : search.trim();
        if (q != null && q.length() > AdminOrderFilter.MAX_SEARCH) {
            throw new ApiExceptions.BadRequestException("That search is too long.");
        }
        return new AdminPaymentQueries.Filter(s, m, from, to, q);
    }

    @GetMapping
    @Operation(summary = "Payments, newest first",
            description = "status: SUCCESS, PENDING, FAILED or REFUNDED. method: UPI, PAYPAL or CRYPTO. "
                    + "from/to: India days, inclusive. search: order, email, name, or a payment or refund reference.")
    public ResponseEntity<AdminPaymentQueries.Page> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(queries.search(filter(status, method, from, to, search),
                        Math.max(0, page), Math.max(1, Math.min(size, 100))));
    }

    @GetMapping("/overview")
    @Operation(summary = "This month against last month by status; amounts for admins only")
    public ResponseEntity<AdminPaymentQueries.Overview> overview(@CurrentAccount AccountPrincipal me) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(queries.overview(me != null && me.role() == AccountRole.ADMIN));
    }

    @GetMapping(value = "/export", produces = "text/csv")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Download the filtered payments as CSV (admin)")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String search,
            @CurrentAccount AccountPrincipal admin) {
        List<AdminPaymentQueries.Row> rows = queries.export(filter(status, method, from, to, search), EXPORT_CAP);
        Csv csv = new Csv().row(List.of("Submitted (IST)", "Order", "Customer name", "Email", "Method",
                "Paid to", "Reference", "Currency", "Amount", "Status", "Order status", "Reviewed (IST)",
                "Reviewed by", "Review note", "Refund reference", "Refunded (IST)", "Refund reason"));
        for (AdminPaymentQueries.Row r : rows) {
            List<Object> cells = new ArrayList<>();
            cells.add(time(r.submittedAt()));
            cells.add(r.publicRef());
            cells.add(r.customerName());
            cells.add(r.email());
            cells.add(r.method());
            cells.add(r.destination());
            cells.add(r.reference());
            cells.add(r.currency());
            cells.add(Money.ofMinor(r.amountMinor(), Currency.valueOf(r.currency())).toMajor());
            cells.add(r.status());
            cells.add(r.orderStatus());
            cells.add(time(r.reviewedAt()));
            cells.add(r.reviewedBy());
            cells.add(r.reviewNote());
            cells.add(r.refund() == null ? null : r.refund().reference());
            cells.add(r.refund() == null ? null : time(r.refund().at()));
            cells.add(r.refund() == null ? null : r.refund().reason());
            csv.row(cells);
        }
        log.info("Admin {} exported {} payment(s){}", admin.publicId(), rows.size(),
                rows.size() >= EXPORT_CAP ? " (capped)" : "");
        String name = "payments-" + LocalDate.now(AdminOrderQueries.BUSINESS_ZONE) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.bytes());
    }

    public record RefundRequest(
            @NotBlank(message = "Which order?") @Size(max = 32) String publicRef,
            @NotBlank(message = "How was the money sent back?") String method,
            @NotBlank(message = "The reference of the money sent back is required")
            @Size(max = 120, message = "Keep the reference under 120 characters")
            String reference,
            @NotBlank(message = "Say why, for the record")
            @Size(max = 500, message = "Keep the reason under 500 characters")
            String reason) {
    }

    public record RefundRecorded(String publicRef, long amountMinor, String amountFormatted, String method,
                                 String reference, Instant at) {
    }

    /**
     * Records money sent back to a customer, and marks the order Refunded.
     *
     * <p>The full order total, in the order's currency: the amount is not typed, so it
     * cannot be mistyped. Nothing is sent to the customer from here except the usual
     * "Order refunded" line on their order page and bell; the money itself was sent by hand.
     */
    @PostMapping("/refunds")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Record a refund sent by hand and mark the order Refunded (admin)")
    public ResponseEntity<RefundRecorded> refund(@Valid @RequestBody RefundRequest request,
                                                 @CurrentAccount AccountPrincipal admin) {
        ManualPaymentMethod method;
        try {
            method = ManualPaymentMethod.valueOf(request.method().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiExceptions.BadRequestException("Unknown refund method.");
        }
        RefundEntity refund = refunds.record(request.publicRef().trim(), method, request.reference(),
                request.reason(), admin.id(), admin.publicId());
        return ResponseEntity.status(HttpStatus.CREATED).body(new RefundRecorded(request.publicRef().trim(),
                refund.getAmountMinor(), Money.ofMinor(refund.getAmountMinor(), refund.getCurrency()).format(),
                refund.getMethod().name(), refund.getReference(), refund.getCreatedAt()));
    }

    private static String time(Instant at) {
        return at == null ? null : TIME.format(at);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
