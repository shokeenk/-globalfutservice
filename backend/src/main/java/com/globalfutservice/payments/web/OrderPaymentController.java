package com.globalfutservice.payments.web;

import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.payments.ManualPaymentMethod;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.payments.ManualPaymentClaimEntity;
import com.globalfutservice.payments.ManualPaymentProofEntity;
import com.globalfutservice.payments.ResumePaymentService;
import com.globalfutservice.payments.payop.PayopCheckoutService;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.Locale;

/**
 * "Complete your payment": paying for one of the signed-in customer's own unpaid orders.
 *
 * <p>The signed-in owner only, by the same rule as viewing the order: it is loaded with its
 * owner in the query, so somebody else's order and one that does not exist are the same
 * "not found". Unlike checkout's payment endpoints, no reference-and-email pair is accepted
 * here. Every action refuses an order that is not waiting for payment.
 *
 * <p>No amount is ever taken from the browser: totals come from the server, and a Payop
 * payment is started from a token the server issued for one method at one price.
 */
@RestController
@RequestMapping("/api/v1/orders/{publicRef}/payment")
@Tag(name = "Order payment", description = "Complete the payment of an unpaid order")
public class OrderPaymentController {

    public record PayopOptionsRequest(@NotBlank @Size(max = 2) String country) {
    }

    public record PayopStartRequest(@NotBlank @Size(max = 2048) String token, @Size(max = 5) String language) {
    }

    public record ClaimRequest(@NotBlank @Size(max = 32) String method,
                               @NotBlank @Size(min = 4, max = 120,
                                       message = "That reference looks too short — check your payment app")
                               String reference) {
    }

    public record SlotRequest(@Size(max = 64) String coachId, Instant startsAt, @Size(max = 64) String timezone) {
    }

    public record StartResponse(String redirectUrl, String invoiceId, long totalMinor, String totalFormatted,
                                Instant payableUntil) {
    }

    private final ResumePaymentService payments;
    private final OrderService orders;

    public OrderPaymentController(ResumePaymentService payments, OrderService orders) {
        this.payments = payments;
        this.orders = orders;
    }

    @GetMapping
    @Operation(summary = "Where the order stands on payment, and everything needed to pay it")
    public ResponseEntity<ResumePaymentService.View> view(@PathVariable String publicRef,
                                                          @RequestParam(required = false) String lang,
                                                          @CurrentAccount AccountPrincipal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(payments.view(owned(publicRef, principal), lang));
    }

    @PostMapping("/payop/options")
    @Operation(summary = "The Payop methods for a country, each priced for this order, with a token to start it")
    public ResponseEntity<ResumePaymentService.PayopOptions> payopOptions(
            @PathVariable String publicRef, @Valid @RequestBody PayopOptionsRequest request,
            @CurrentAccount AccountPrincipal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(payments.payopOptions(owned(publicRef, principal), request.country()));
    }

    @PostMapping("/payop/invoices")
    @Operation(summary = "Start paying with the Payop method a token names; returns the page to send the customer to")
    public ResponseEntity<StartResponse> startPayop(@PathVariable String publicRef,
                                                    @Valid @RequestBody PayopStartRequest request,
                                                    @CurrentAccount AccountPrincipal principal) {
        OrderEntity order = owned(publicRef, principal);
        PayopCheckoutService.Started s = payments.startPayop(order, request.token(), request.language());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(
                new StartResponse(s.redirectUrl(), s.invoiceId(), s.totalMinor(),
                        Money.ofMinor(s.totalMinor(), order.getCurrency()).format(), s.payableUntil()));
    }

    @PostMapping("/claims")
    @Operation(summary = "Tell us you have paid with UPI, PayPal or crypto: the same claim as at checkout")
    public ResponseEntity<ManualPaymentDtos.ClaimResponse> submitClaim(@PathVariable String publicRef,
                                                                       @Valid @RequestBody ClaimRequest request,
                                                                       @CurrentAccount AccountPrincipal principal) {
        ManualPaymentClaimEntity claim = payments.submitClaim(owned(publicRef, principal),
                method(request.method()), request.reference());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ManualPaymentDtos.ClaimResponse.of(claim));
    }

    @PostMapping(value = "/claims/proof", consumes = "multipart/form-data")
    @Operation(summary = "Attach the screenshot of the payment to the claim just made")
    public ResponseEntity<ManualPaymentDtos.ProofResponse> attachProof(@PathVariable String publicRef,
                                                                       @RequestParam("file") MultipartFile file,
                                                                       @CurrentAccount AccountPrincipal principal)
            throws IOException {
        ManualPaymentProofEntity proof = payments.attachProof(owned(publicRef, principal), file.getBytes());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new ManualPaymentDtos.ProofResponse(proof.getContentType(), proof.getSizeBytes(),
                        proof.getUploadedAt()));
    }

    @PostMapping("/coaching-slot")
    @Operation(summary = "Hold a session slot again: the one the order had, or a newly picked one")
    public ResponseEntity<ResumePaymentService.CoachingSlot> holdSlot(@PathVariable String publicRef,
                                                                      @Valid @RequestBody SlotRequest request,
                                                                      @CurrentAccount AccountPrincipal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(payments.holdSlot(
                owned(publicRef, principal), request.coachId(), request.startsAt(), request.timezone()));
    }

    /** The signed-in customer's own order, or "not found" for anybody else's and for none. */
    private OrderEntity owned(String publicRef, AccountPrincipal principal) {
        if (principal == null) {
            throw new ApiExceptions.ForbiddenException("Please sign in.");
        }
        return orders.requireOwned(publicRef.trim().toUpperCase(Locale.ROOT), principal.id());
    }

    private static ManualPaymentMethod method(String raw) {
        try {
            return ManualPaymentMethod.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new ApiExceptions.BadRequestException("unknown_payment_method", "Unknown payment method.");
        }
    }
}
