package com.globalfutservice.orders.web;

import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.credentials.web.CredentialDtos;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.fulfilment.SupplierFulfilmentService;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Placing, tracking and completing orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderRepository orders;
    private final OrderMapper mapper;
    private final AccountRepository accounts;
    private final CredentialVaultService vaultService;
    private final SupplierFulfilmentService supplierFulfilment;

    public OrderController(OrderService orderService, OrderRepository orders, OrderMapper mapper,
                           SupplierFulfilmentService supplierFulfilment,
                           AccountRepository accounts, CredentialVaultService vaultService) {
        this.orderService = orderService;
        this.orders = orders;
        this.mapper = mapper;
        this.accounts = accounts;
        this.vaultService = vaultService;
        this.supplierFulfilment = supplierFulfilment;
    }

    @PostMapping
    @Operation(summary = "Place an order from a signed quote",
            description = "Open to guests. The quote's signature and expiry are re-checked "
                    + "server-side; a tampered or stale quote is refused with 409.")
    public ResponseEntity<OrderDtos.CreateOrderResponse> create(
            @Valid @RequestBody OrderDtos.CreateOrderRequest request,
            @CurrentAccount AccountPrincipal principal) {

        AccountEntity account = principal == null
                ? null : accounts.findById(principal.id()).orElse(null);

        OrderDtos.CreateOrderResponse response = orderService.create(request, account);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }

    @PostMapping("/track")
    @Operation(summary = "Look up a guest order",
            description = "Reference plus email. The reference alone is not a credential — "
                    + "it appears in email subjects, screenshots and support chats.")
    public ResponseEntity<OrderDtos.OrderResponse> track(
            @Valid @RequestBody OrderDtos.TrackOrderRequest request) {
        OrderEntity order = orderService.requireGuest(request.publicRef(), request.email());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(mapper.toResponse(order, orderService.timeline(order.getId()),
                        vaultService.status(order.getId()).present()));
    }

    @GetMapping
    @Operation(summary = "The signed-in customer's order history")
    public ResponseEntity<List<OrderDtos.AdminOrderSummary>> mine(
            @CurrentAccount AccountPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        requireSignedIn(principal);
        Page<OrderEntity> found = orders.findByAccountIdOrderByCreatedAtDesc(
                principal.id(), PageRequest.of(Math.max(0, page), Math.min(size, 100)));
        return ResponseEntity.ok(found.getContent().stream()
                .map(o -> mapper.toAdminSummary(o, false))
                .toList());
    }

    @GetMapping("/{publicRef}")
    @Operation(summary = "One of the signed-in customer's orders")
    public ResponseEntity<OrderDtos.OrderResponse> one(
            @PathVariable String publicRef,
            @CurrentAccount AccountPrincipal principal) {

        requireSignedIn(principal);
        // Ownership is in the query. There is no path here that loads an order and then
        // decides whether the caller was allowed to see it.
        OrderEntity order = orderService.requireOwned(publicRef, principal.id());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(mapper.toResponse(order, orderService.timeline(order.getId()),
                        vaultService.status(order.getId()).present()));
    }

    @PostMapping("/{publicRef}/credentials")
    @Operation(summary = "Submit EA sign-in for a comfort-trade order",
            description = """
                    Only accepted on a paid order that is waiting for it.

                    The payload is sealed with a per-order data key before it reaches the
                    database, is never written to a log, and is destroyed on completion or
                    within the retention window, whichever comes first.
                    """)
    public ResponseEntity<OrderDtos.OrderResponse> submitCredentials(
            @PathVariable String publicRef,
            @Valid @RequestBody CredentialDtos.SubmitCredentialsRequest request,
            @CurrentAccount AccountPrincipal principal) {

        requireSignedIn(principal);
        OrderEntity order = orderService.requireOwned(publicRef, principal.id());
        OrderEntity updated = orderService.submitCredentials(order, request, principal.id());

        /*
          Handed to the supplier here rather than inside `submitCredentials`, because the
          supplier's poller has to call OrderService to move orders through the state
          machine — wiring dispatch the other way would close a dependency cycle between
          the two. Orchestrating at the edge keeps each direction one-way.

          It never throws into this response. The customer has paid and given us
          everything asked of them; a supplier outage is our problem to fix by hand, not a
          red error on a form they cannot retry.
        */
        supplierFulfilment.dispatch(updated);

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(mapper.toResponse(updated, orderService.timeline(updated.getId()), true));
    }

    private static void requireSignedIn(AccountPrincipal principal) {
        if (principal == null) {
            throw new ApiExceptions.ForbiddenException("Please sign in.");
        }
    }
}
