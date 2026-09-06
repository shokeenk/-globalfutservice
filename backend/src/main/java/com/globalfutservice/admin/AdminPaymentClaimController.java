package com.globalfutservice.admin;

import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.payments.ManualPaymentClaimEntity;
import com.globalfutservice.payments.ManualPaymentService;
import com.globalfutservice.payments.web.ManualPaymentDtos;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.util.List;

/**
 * The review desk for payments made outside the gateway.
 *
 * <p>Without this the manual payment flow does not work at all: customers would submit
 * references into a table nobody reads and their orders would sit in AWAITING_PAYMENT
 * forever. Verifying here is the only thing that marks such an order paid.
 *
 * <p>Secured by {@code /api/v1/admin/**} requiring ROLE_OPERATOR in SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/admin/payment-claims")
@Tag(name = "Admin — payment claims", description = "Verifying payments made outside the gateway")
public class AdminPaymentClaimController {

    private static final Logger log = LoggerFactory.getLogger(AdminPaymentClaimController.class);

    private final ManualPaymentService manualPayments;
    private final OrderService orderService;

    public AdminPaymentClaimController(ManualPaymentService manualPayments, OrderService orderService) {
        this.manualPayments = manualPayments;
        this.orderService = orderService;
    }

    @GetMapping
    @Operation(summary = "Claims waiting to be checked, oldest first")
    public ResponseEntity<List<ManualPaymentDtos.AdminClaimResponse>> pending(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        List<ManualPaymentDtos.AdminClaimResponse> rows =
                manualPayments.pending(PageRequest.of(Math.max(0, page), Math.min(size, 100)))
                        .getContent().stream()
                        .map(this::toRow)
                        .toList();

        // Never cached. An operator refreshing this page after verifying something must
        // not be shown the row they just cleared.
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(rows);
    }

    @GetMapping("/count")
    @Operation(summary = "How many claims are waiting")
    public ResponseEntity<Long> pendingCount() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(manualPayments.pendingCount());
    }

    @GetMapping("/search")
    @Operation(summary = "Find claims by the reference on a bank statement line",
            description = "Returns every claim quoting that reference. More than one "
                    + "result is a real outcome and means two customers quoted the same "
                    + "number -- at most one of them correctly.")
    public ResponseEntity<List<ManualPaymentDtos.AdminClaimResponse>> byReference(
            @RequestParam String reference) {

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(manualPayments.byReference(reference).stream().map(this::toRow).toList());
    }

    @GetMapping("/order/{publicRef}")
    @Operation(summary = "Every claim on one order, including rejected attempts")
    public ResponseEntity<List<ManualPaymentDtos.AdminClaimResponse>> forOrder(
            @PathVariable String publicRef) {

        OrderEntity order = orderService.requireAny(publicRef);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(manualPayments.forOrder(order.getId()).stream().map(this::toRow).toList());
    }

    @PostMapping("/{claimId}/verify")
    @Operation(summary = "The money is there — release the order",
            description = "Marks the order paid through the same path the gateway webhook "
                    + "uses, so credentials handling and coaching credits behave identically.")
    public ResponseEntity<ManualPaymentDtos.AdminClaimResponse> verify(
            @PathVariable Long claimId,
            @Valid @RequestBody(required = false) ManualPaymentDtos.ReviewClaimRequest request,
            @CurrentAccount AccountPrincipal operator) {

        log.info("Operator {} is verifying payment claim {}", operator.id(), claimId);
        ManualPaymentClaimEntity claim =
                manualPayments.verify(claimId, operator.id(), request == null ? null : request.note());

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(toRow(claim));
    }

    @PostMapping("/{claimId}/reject")
    @Operation(summary = "The money is not there — leave the order unpaid",
            description = "The order does not move, so the customer can submit a "
                    + "corrected reference without support reopening anything.")
    public ResponseEntity<ManualPaymentDtos.AdminClaimResponse> reject(
            @PathVariable Long claimId,
            @Valid @RequestBody(required = false) ManualPaymentDtos.ReviewClaimRequest request,
            @CurrentAccount AccountPrincipal operator) {

        log.info("Operator {} is rejecting payment claim {}", operator.id(), claimId);
        ManualPaymentClaimEntity claim =
                manualPayments.reject(claimId, operator.id(), request == null ? null : request.note());

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(toRow(claim));
    }

    /**
     * Joins the claim to its order for the queue.
     *
     * <p>Done per row rather than in a query because the queue is small by nature -- it
     * is bounded by how fast a person can work through it, not by order volume.
     */
    private ManualPaymentDtos.AdminClaimResponse toRow(ManualPaymentClaimEntity claim) {
        OrderEntity order = orderService.requireById(claim.getOrderId());
        return new ManualPaymentDtos.AdminClaimResponse(
                claim.getId(),
                order.getPublicRef(),
                order.getGuestEmail(),
                order.getSku().name(),
                order.getTotalMinor(),
                order.total().format(),
                order.getCurrency().name(),
                claim.getMethod().name(),
                claim.getDestination(),
                claim.getReference(),
                claim.getStatus().name(),
                claim.getSubmittedAt(),
                claim.getReviewedAt(),
                claim.getReviewNote());
    }
}
