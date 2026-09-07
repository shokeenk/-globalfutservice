package com.globalfutservice.payments.web;

import com.globalfutservice.domain.payments.ManualPaymentMethod;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.payments.ManualPaymentClaimEntity;
import com.globalfutservice.payments.ManualPaymentProofEntity;
import com.globalfutservice.payments.ManualPaymentService;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Paying outside the gateway, and saying that you did")
public class ManualPaymentController {

    private final ManualPaymentService manualPayments;
    private final OrderService orderService;

    public ManualPaymentController(ManualPaymentService manualPayments, OrderService orderService) {
        this.manualPayments = manualPayments;
        this.orderService = orderService;
    }

    @GetMapping("/methods")
    @Operation(summary = "Payment destinations offered for a sku",
            description = """
                    Open. The addresses here are printed on the checkout page and encoded
                    in the QR codes beside them, so there is nothing to withhold.

                    The domestic UPI destination depends on the sku; PayPal and crypto are
                    offered to everybody. A method with no address configured is omitted.
                    """)
    public ResponseEntity<List<ManualPaymentDtos.PaymentOptionResponse>> methods(
            @RequestParam String sku) {

        List<ManualPaymentDtos.PaymentOptionResponse> options =
                manualPayments.optionsFor(sku).stream()
                        .map(ManualPaymentDtos.PaymentOptionResponse::of)
                        .toList();

        /*
         * Cached briefly and publicly, like the rate card. These change about never, but
         * when one does change it is because money is going to the wrong place, so the
         * window to a corrected address is a minute rather than an hour.
         */
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePublic())
                .body(options);
    }

    @PostMapping("/claims/{publicRef}")
    @Operation(summary = "Tell us you have paid outside the gateway",
            description = """
                    Records the reference the customer's payment app gave them. Open to
                    guests, authenticated the same way order tracking is -- reference plus
                    the email on the order.

                    This does NOT mark the order paid. Nothing here is verified: the
                    reference is a string the customer typed, and an operator has to find
                    the money before the order moves. Resubmitting replaces the pending
                    claim, so a mistyped reference can be corrected.
                    """)
    public ResponseEntity<ManualPaymentDtos.ClaimResponse> submitClaim(
            @PathVariable String publicRef,
            @Valid @RequestBody ManualPaymentDtos.SubmitClaimRequest request) {

        OrderEntity order = orderService.requireGuest(publicRef, request.email());
        ManualPaymentMethod method = parseMethod(request.method());

        ManualPaymentClaimEntity claim = manualPayments.submit(order, method, request.reference());

        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ManualPaymentDtos.ClaimResponse.of(claim));
    }

    @PostMapping(value = "/claims/{publicRef}/proof", consumes = "multipart/form-data")
    @Operation(summary = "Attach a screenshot of the payment",
            description = """
                    Optional. A reference alone cannot be told apart from a typo until an
                    operator checks the account; the screenshot usually settles it in
                    seconds because it carries the amount, the time and the destination as
                    the customer's own app rendered them.

                    Accepts JPG, PNG or WebP up to 5 MB. The type is decided from the
                    file's own bytes, not from the Content-Type the browser sent.
                    Uploading again replaces the previous image.
                    """)
    public ResponseEntity<ManualPaymentDtos.ProofResponse> attachProof(
            @PathVariable String publicRef,
            @RequestParam("email") String email,
            @RequestParam("file") MultipartFile file) throws IOException {

        // Same guest auth as the claim itself: the reference is not a credential, the
        // pair is. Sent as a form field because this is a multipart request.
        OrderEntity order = orderService.requireGuest(publicRef, email);

        ManualPaymentProofEntity proof = manualPayments.attachProof(order, file.getBytes());

        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ManualPaymentDtos.ProofResponse(
                        proof.getContentType(), proof.getSizeBytes(), proof.getUploadedAt()));
    }

    private static ManualPaymentMethod parseMethod(String raw) {
        try {
            return ManualPaymentMethod.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new ApiExceptions.BadRequestException(
                    "unknown_payment_method", "Unknown payment method.");
        }
    }
}
