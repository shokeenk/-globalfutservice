package com.globalfutservice.orders.web;

import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.orders.result.OrderResultService;
import com.globalfutservice.orders.result.ResultDtos;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The customer's side of a Champions result: choosing how the difference is settled. */
@RestController
@RequestMapping("/api/v1/orders/{publicRef}/result")
@Tag(name = "Orders", description = "Placing, tracking and completing orders")
public class OrderResultController {

    private final OrderService orders;
    private final OrderResultService results;
    private final OrderMapper mapper;
    private final CredentialVaultService vault;

    public OrderResultController(OrderService orders, OrderResultService results, OrderMapper mapper,
                                 CredentialVaultService vault) {
        this.orders = orders;
        this.results = results;
        this.mapper = mapper;
        this.vault = vault;
    }

    @PostMapping("/choice")
    @Operation(summary = "Choose a refund or GFS Store Credit for wins not achieved (the order's owner, once)")
    public ResponseEntity<OrderDtos.OrderResponse> choose(@PathVariable String publicRef,
                                                          @Valid @RequestBody ResultDtos.ChoiceRequest request,
                                                          @CurrentAccount AccountPrincipal principal) {
        if (principal == null) {
            throw new ApiExceptions.ForbiddenException("Please sign in.");
        }
        // Ownership is in the lookup: anyone else's order is "not found".
        OrderEntity order = orders.requireOwned(publicRef, principal.id());
        results.choose(order, request.method(), principal.id());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(mapper.toResponse(order, orders.timeline(order.getId()), vault.status(order.getId()).present()));
    }
}
