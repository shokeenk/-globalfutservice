package com.globalfutservice.admin;

import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.credentials.web.CredentialDtos;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.orders.Actor;
import com.globalfutservice.domain.orders.OrderStateMachine;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.fulfilment.SupplierFulfilmentService;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    public AdminOrderController(OrderRepository orders, OrderService orderService,
                                OrderMapper mapper, CredentialVaultService vaultService,
                                SupplierFulfilmentService supplierFulfilment) {
        this.supplierFulfilment = supplierFulfilment;
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

    @GetMapping("/stats")
    @Operation(summary = "Queue counts and recent revenue")
    public ResponseEntity<OrderDtos.AdminStats> stats() {
        Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);
        long revenue = orders.revenueSince(thirtyDaysAgo);
        return ResponseEntity.ok(new OrderDtos.AdminStats(
                orders.countByStatus(OrderStatus.AWAITING_PAYMENT),
                orders.countByStatus(OrderStatus.PAID),
                orders.countByStatus(OrderStatus.CREDENTIALS_PENDING),
                orders.countByStatus(OrderStatus.READY_FOR_DELIVERY),
                orders.countByStatus(OrderStatus.IN_PROGRESS),
                orders.countByStatus(OrderStatus.ON_HOLD),
                orders.countByStatus(OrderStatus.DELIVERED),
                orders.countByStatus(OrderStatus.DISPUTED),
                vaultService.countHeld(),
                revenue,
                Money.ofMinor(revenue, Currency.INR).format()));
    }

    @GetMapping("/{publicRef}")
    @Operation(summary = "One order with its full timeline")
    public ResponseEntity<OrderDtos.OrderResponse> one(@PathVariable String publicRef) {
        OrderEntity order = orderService.requireAny(publicRef);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(mapper.toResponse(order, orderService.timeline(order.getId()),
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
                .body(mapper.toResponse(updated, orderService.timeline(updated.getId()),
                        vaultService.status(updated.getId()).present()));
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
                    outside our infrastructure. It is deliberately a human decision: the
                    checkout no longer dispatches automatically.

                    The sign-in is decrypted in memory for the duration of one outbound
                    call and is never logged, never persisted in plaintext and never
                    returned in this response. On failure the order does not move and the
                    partner's reason is surfaced.
                    """)
    public ResponseEntity<OrderDtos.OrderResponse> approveFulfilment(
            @PathVariable String publicRef,
            @CurrentAccount AccountPrincipal operator) {

        OrderEntity order = orderService.requireAny(publicRef);

        if (!order.getSku().isCoinTransfer()) {
            /*
             * The partner takes coin orders. Boosting holds a sign-in exactly like a coin
             * order does, so it arrived here looking releasable and failed inside the
             * client instead -- burning a dispatch attempt to say so.
             */
            throw new ApiExceptions.ConflictException("not_a_coin_order",
                    "The fulfilment partner only takes coin orders. This one is "
                            + order.getSku().displayName() + ", which is worked by hand.");
        }

        /*
         * READY_FOR_DELIVERY is the approval state, and it already existed.
         *
         * It means exactly what an "awaiting admin approval" status would: paid, sign-in
         * held, nothing started. Adding a second status with that meaning would have made
         * every order already sitting in this state ambiguous and bought no behaviour, so
         * the existing one is used and the state machine's READY_FOR_DELIVERY ->
         * IN_PROGRESS edge carries the approval.
         */
        if (order.getStatus() != OrderStatus.READY_FOR_DELIVERY) {
            throw new ApiExceptions.ConflictException("not_awaiting_approval",
                    "Only an order that is paid and holding a sign-in can be released. "
                            + "This one is " + order.getStatus().name() + ".");
        }
        if (!vaultService.status(order.getId()).present()) {
            // The partner requires the sign-in; releasing without one would be a
            // guaranteed 400 from them and a wasted dispatch attempt against the order.
            throw new ApiExceptions.ConflictException("no_credentials",
                    "This order has no sign-in on file, so there is nothing to send.");
        }

        // Throws with the partner's reason if refused. The order stays put in that case:
        // the transition below is only reached on a supplier order id.
        String supplierOrderId = supplierFulfilment.approveAndDispatch(order, operator.id());

        /*
         * Re-read before moving it, because the release just wrote to this row.
         *
         * `approveAndDispatch` stores the partner's order id and commits, which leaves the
         * copy loaded above one version behind. Transitioning that stale copy failed the
         * optimistic lock *after* the sign-in had already gone to the partner: the
         * operator saw a 500, the order sat in the queue, and only a second click moved
         * it. Loading it again costs one query and makes the successful path succeed.
         */
        OrderEntity released = orderService.requireAny(publicRef);

        OrderEntity moved = orderService.transition(released, OrderStatus.IN_PROGRESS,
                Actor.OPERATOR, operator.id(), operator.email(),
                "Released to fulfilment partner as " + supplierOrderId);

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(mapper.toResponse(moved, orderService.timeline(moved.getId()), true));
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
