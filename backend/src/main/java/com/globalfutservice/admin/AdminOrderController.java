package com.globalfutservice.admin;

import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.credentials.web.CredentialDtos;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.orders.Actor;
import com.globalfutservice.domain.orders.OrderStateMachine;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.fulfilment.FulfilmentRelease;
import com.globalfutservice.fulfilment.FutTransferClient;
import com.globalfutservice.fulfilment.SupplierFulfilmentService;
import com.globalfutservice.fulfilment.VendorOrderActions;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.orders.web.OrderDtos;
import com.globalfutservice.orders.web.OrderMapper;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
 * The operations console.
 *
 * <p>The client lives in this screen, so it gets more care than the homepage: the queue,
 * the timeline, the transition buttons and the vault are all here.
 *
 * <p>The set of transitions offered is computed by the state machine rather than
 * hard-coded in the UI, and it deliberately excludes PAID, COMPLETED and ABANDONED — an
 * operator must not be able to mark an unpaid order paid "just to unblock the customer",
 * because that is how a business delivers goods against a payment that never arrived.
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
@Tag(name = "Admin — orders", description = "Fulfilment queue and order actions")
public class AdminOrderController {

    private static final Logger log = LoggerFactory.getLogger(AdminOrderController.class);

    private final OrderRepository orders;
    private final OrderService orderService;
    private final OrderMapper mapper;
    private final CredentialVaultService vaultService;
    private final SupplierFulfilmentService supplierFulfilment;
    private final AdminOrderQueries queries;
    private final FulfilmentRelease release;
    private final AccountRepository accounts;

    public AdminOrderController(OrderRepository orders, OrderService orderService,
                                OrderMapper mapper, CredentialVaultService vaultService,
                                SupplierFulfilmentService supplierFulfilment,
                                AdminOrderQueries queries, FulfilmentRelease release,
                                AccountRepository accounts) {
        this.release = release;
        this.accounts = accounts;
        this.supplierFulfilment = supplierFulfilment;
        this.queries = queries;
        this.orders = orders;
        this.orderService = orderService;
        this.mapper = mapper;
        this.vaultService = vaultService;
    }

    @GetMapping
    @Operation(summary = "The fulfilment queue")
    public ResponseEntity<List<OrderDtos.AdminOrderSummary>> queue(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        OrderStatus parsed = null;
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            try {
                parsed = OrderStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ApiExceptions.BadRequestException("Unknown status filter.");
            }
        }

        Page<OrderEntity> found = orders.findForAdmin(parsed,
                search == null || search.isBlank() ? null : search.trim(),
                PageRequest.of(Math.max(0, page), Math.min(size, 100)));

        return ResponseEntity.ok(found.getContent().stream()
                .map(o -> mapper.toAdminSummary(o, vaultService.status(o.getId()).present()))
                .toList());
    }

    /**
     * The queue counters.
     *
     * <p>Revenue used to ride along here, which put it in front of every operator: this is
     * polled by the Orders page every twenty seconds. It is on
     * {@link AdminAnalyticsController#revenue} now, which is ADMIN only.
     */
    @GetMapping("/stats")
    @Operation(summary = "Queue counts")
    public ResponseEntity<OrderDtos.AdminStats> stats() {
        return ResponseEntity.ok(new OrderDtos.AdminStats(
                orders.countByStatus(OrderStatus.AWAITING_PAYMENT),
                orders.countByStatus(OrderStatus.PAID),
                orders.countByStatus(OrderStatus.CREDENTIALS_PENDING),
                orders.countByStatus(OrderStatus.READY_FOR_DELIVERY),
                orders.countByStatus(OrderStatus.IN_PROGRESS),
                orders.countByStatus(OrderStatus.ON_HOLD),
                orders.countByStatus(OrderStatus.DELIVERED),
                orders.countByStatus(OrderStatus.DISPUTED),
                vaultService.countHeld()));
    }

    /**
     * The Orders page's table: filtered, newest first, with the total the pagination needs.
     *
     * <p>A separate path from the queue above rather than a change to it. The queue's
     * shape is also read by the order page, and that caller is left exactly as it was.
     */
    @GetMapping("/search")
    @Operation(summary = "The order table, filtered and paged",
            description = "service: COINS, BOOSTING, CHAMPS, RIVALS or COACHING. status: one "
                    + "or more statuses, comma-separated. from/to: whole days in India time, "
                    + "inclusive. attention: only orders that need a person now.")
    public ResponseEntity<AdminOrderViews.Page> search(
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "false") boolean attention,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        AdminOrderFilter filter = AdminOrderFilter.parse(service, status, platform, from, to,
                search, attention, AdminOrderQueries.BUSINESS_ZONE);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(queries.search(filter, Math.max(0, page), Math.max(1, Math.min(size, 100))));
    }

    /** The cards and tab counts above the table. Counts only: no money. */
    @GetMapping("/overview")
    @Operation(summary = "Counts for the Orders page's cards and tabs")
    public ResponseEntity<AdminOrderViews.Overview> overview() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(queries.overview());
    }

    /** At most this many rows in one file; the filters narrow it. */
    static final int EXPORT_CAP = 10_000;

    private static final DateTimeFormatter EXPORT_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(AdminOrderQueries.BUSINESS_ZONE);

    /**
     * The filtered table as a CSV file.
     *
     * <p>ADMIN only. A file of orders with their amounts adds up to revenue, which is an
     * admin's figure; an operator has every row on screen already.
     */
    @GetMapping(value = "/export", produces = "text/csv")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Download the filtered orders as CSV (admin)")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "false") boolean attention,
            @CurrentAccount AccountPrincipal admin) {
        AdminOrderFilter filter = AdminOrderFilter.parse(service, status, platform, from, to,
                search, attention, AdminOrderQueries.BUSINESS_ZONE);
        List<AdminOrderViews.Row> rows = queries.export(filter, EXPORT_CAP);

        Csv csv = new Csv().row(List.of("Reference", "Placed (IST)", "Status", "Service", "SKU",
                "Variant", "Quantity", "Platform", "Customer name", "Email", "EA ID", "Currency",
                "Total", "Payment method", "Payment reference", "Payment state", "Delivered (IST)"));
        for (AdminOrderViews.Row row : rows) {
            List<Object> cells = new ArrayList<>();
            cells.add(row.publicRef());
            cells.add(time(row.createdAt()));
            cells.add(row.status());
            cells.add(row.serviceLabel());
            cells.add(row.sku());
            cells.add(row.variant());
            cells.add(row.quantity());
            cells.add(row.platform());
            cells.add(row.customerName());
            cells.add(row.customerEmail());
            cells.add(row.eaHandle());
            cells.add(row.currency());
            cells.add(Money.ofMinor(row.totalMinor(), Currency.valueOf(row.currency())).toMajor());
            cells.add(row.paymentMethod());
            cells.add(row.paymentReference());
            cells.add(row.paymentState());
            cells.add(time(row.deliveredAt()));
            csv.row(cells);
        }

        log.info("Admin {} exported {} order(s){}", admin.publicId(), rows.size(),
                rows.size() >= EXPORT_CAP ? " (capped)" : "");
        String name = "orders-" + LocalDate.now(AdminOrderQueries.BUSINESS_ZONE) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.bytes());
    }

    private static String time(Instant at) {
        return at == null ? null : EXPORT_TIME.format(at);
    }

    /**
     * Emails the customer the sign-in request again.
     *
     * <p>Operator, like every other queue action. The order's status does not change; the
     * reminder is written to its timeline, and the customer sees that line on their page.
     */
    @PostMapping("/{publicRef}/credentials/remind")
    @Operation(summary = "Remind the customer to send their EA sign-in",
            description = "At most once every six hours per order. Refused when the order is "
                    + "not waiting for a sign-in or already has one.")
    public ResponseEntity<AdminOrderViews.ReminderSent> remindCredentials(
            @PathVariable String publicRef,
            @CurrentAccount AccountPrincipal operator) {
        OrderEntity order = orderService.requireAny(publicRef);
        Instant sentAt = orderService.remindCredentials(order, operator.id(), operator.publicId());
        return ResponseEntity.ok(new AdminOrderViews.ReminderSent(sentAt));
    }

    @GetMapping("/{publicRef}")
    @Operation(summary = "One order with its full timeline")
    public ResponseEntity<OrderDtos.OrderResponse> one(@PathVariable String publicRef) {
        OrderEntity order = orderService.requireAny(publicRef);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(mapper.toAdminResponse(order, orderService.timeline(order.getId()),
                        vaultService.status(order.getId()).present()));
    }

    @PostMapping("/{publicRef}/transition")
    @Operation(summary = "Move an order to another state",
            description = "Validated against the state machine. DELIVERED is irreversible: "
                    + "it sends the delivery email, closes the refund window and starts the "
                    + "guarantee clock.")
    public ResponseEntity<OrderDtos.OrderResponse> transition(
            @PathVariable String publicRef,
            @Valid @RequestBody OrderDtos.TransitionRequest request,
            @CurrentAccount AccountPrincipal operator) {

        OrderEntity order = orderService.requireAny(publicRef);
        OrderStatus target;
        try {
            target = OrderStatus.valueOf(request.toStatus().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiExceptions.BadRequestException("Unknown status.");
        }

        // Belt and braces: the state machine would reject an illegal move anyway, but an
        // operator must additionally be restricted to the subset that is theirs to make.
        if (!OrderStateMachine.operatorTransitions(order.getStatus()).contains(target)) {
            throw new ApiExceptions.ConflictException("invalid_transition",
                    "That action is not available for this order.");
        }

        OrderEntity updated = orderService.transition(order, target, Actor.OPERATOR,
                operator.id(), operator.publicId(), request.reason());

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(mapper.toAdminResponse(updated, orderService.timeline(updated.getId()),
                        vaultService.status(updated.getId()).present()));
    }

    /**
     * How Approve would send this order to FUT Transfer, for its confirmation: the mode
     * configured now, and the one the last attempt used. Mode names only; nothing is sent.
     */
    @GetMapping("/{publicRef}/release-preview")
    @Operation(summary = "How Approve would send this order to the fulfilment partner")
    public ResponseEntity<SupplierFulfilmentService.ReleasePreview> releasePreview(@PathVariable String publicRef) {
        OrderEntity order = orderService.requireAny(publicRef);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(supplierFulfilment.preview(order));
    }

    /**
     * Opens the credential vault for one order.
     *
     * <p>A POST rather than a GET on purpose: this is not a safe, idempotent read. It is
     * an audited disclosure of somebody's password, it increments an access counter, and
     * it must never be prefetched by a browser, cached, or land in an access log as a URL
     * somebody can click again.
     */
    @PostMapping("/{publicRef}/approve-fulfilment")
    @Operation(summary = "Release an order to the fulfilment partner (audited)",
            description = """
                    Submits the customer's EA sign-in to the fulfilment partner and moves
                    the order to IN_PROGRESS.

                    This is the only path that shares a customer's account credentials
                    outside our infrastructure. It is a human decision unless
                    GFS_FUTTRANSFER_AUTO_DISPATCH is on; then a paid coin order is sent by this
                    same release without anyone clicking, and Approve remains for any order it
                    left. The order's vendor history says "Sent automatically" or who sent it.

                    The sign-in is decrypted in memory for the duration of one outbound
                    call and is never logged, never persisted in plaintext and never
                    returned in this response.

                    Sent at most once: a second call finds the order already claimed and
                    sends nothing. When the partner's answer is lost the order is looked up
                    by its reference, never sent again; if that cannot confirm it, the order
                    waits for an admin. A refused sign-in is deleted and the order goes on
                    hold for the customer to enter it again. Any other failure leaves the
                    order where it was, with the reason in the error.
                    """)
    public ResponseEntity<OrderDtos.OrderResponse> approveFulfilment(
            @PathVariable String publicRef,
            @CurrentAccount AccountPrincipal operator) {

        // The same release the automatic queue uses: checks, exactly-once send, order moved on.
        FulfilmentRelease.Released released = release.release(publicRef, FulfilmentRelease.Releaser.admin(
                new VendorOrderActions.Admin(operator.id(), labelOf(operator), operator.publicId())));
        if (released.sent()) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(mapper.toAdminResponse(released.order(), orderService.timeline(released.order().getId()),
                            true));
        }
        // Safe to show: written by us, never containing the sign-in.
        throw new FutTransferClient.FutTransferException(released.release().message());
    }

    /**
     * Who clicked, by name, for the order's vendor history (staff only). The access token
     * carries no email, so it is read from the account.
     */
    private String labelOf(AccountPrincipal operator) {
        return operator.email() != null ? operator.email()
                : accounts.findById(operator.id()).map(AccountEntity::getEmail).orElse("account " + operator.id());
    }

    @PostMapping("/{publicRef}/credentials/reveal")
    @Operation(summary = "Reveal the customer's sign-in for fulfilment (audited)")
    public ResponseEntity<CredentialDtos.RevealedCredentials> reveal(
            @PathVariable String publicRef,
            @CurrentAccount AccountPrincipal operator) {

        OrderEntity order = orderService.requireAny(publicRef);
        if (!order.getStatus().mayHoldCredentials()) {
            throw new ApiExceptions.ConflictException("not_available",
                    "This order is not in a state where sign-in details are available.");
        }

        log.info("Operator {} is revealing credentials for order {}",
                operator.publicId(), publicRef);

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(vaultService.reveal(order.getId(), operator.id()));
    }

    @PostMapping("/{publicRef}/credentials/purge")
    @Operation(summary = "Destroy the customer's sign-in immediately")
    public ResponseEntity<Void> purge(@PathVariable String publicRef,
                                      @CurrentAccount AccountPrincipal operator) {
        OrderEntity order = orderService.requireAny(publicRef);
        vaultService.purge(order.getId(), "purged by operator " + operator.publicId());
        return ResponseEntity.noContent().build();
    }
}
