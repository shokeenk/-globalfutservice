package com.globalfutservice.payments.web;

import com.globalfutservice.payments.ManualPaymentClaimEntity;
import com.globalfutservice.payments.ManualPaymentService;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class ManualPaymentDtos {

    private ManualPaymentDtos() {
    }

    /**
     * One payment option, as the storefront renders it.
     *
     * <p>{@code destination} is the payable address itself -- the UPI id, the PayPal
     * link, the wallet -- and is the same string the server will record against the
     * claim. It is served rather than committed to the bundle so the two cannot drift:
     * a page showing an address the server does not recognise is a page that takes
     * money nobody reconciles.
     */
    public record PaymentOptionResponse(
            String method,
            String destination,
            /** Account holder, where the method has one to show. Null for PayPal and crypto. */
            String accountName,
            /** What this method's payers call their reference, e.g. "UTR". */
            String referenceName) {

        public static PaymentOptionResponse of(ManualPaymentService.PaymentOption option) {
            return new PaymentOptionResponse(
                    option.method().name(),
                    option.destination(),
                    option.accountName(),
                    option.method().referenceName());
        }
    }

    public record SubmitClaimRequest(
            /**
             * Guest auth, exactly as {@code /orders/track}: the reference alone is not a
             * credential. Without this anybody who saw an order reference could file a
             * payment claim against somebody else's order.
             */
            @Email(message = "That does not look like an email address")
            @NotBlank(message = "Email is required")
            String email,

            @NotBlank(message = "Choose how you paid")
            @Size(max = 32)
            String method,

            @NotBlank(message = "Enter your payment reference")
            @Size(min = 4, max = 120,
                    message = "That reference looks too short — check your payment app")
            String reference) {
    }

    /**
     * What the customer is told back.
     *
     * <p>Says only that the claim was recorded. It must not read as a receipt: no money
     * has been confirmed at this point and the order has not moved.
     */
    public record ClaimResponse(
            Long id,
            String method,
            String reference,
            String status,
            Instant submittedAt) {

        public static ClaimResponse of(ManualPaymentClaimEntity claim) {
            return new ClaimResponse(
                    claim.getId(),
                    claim.getMethod().name(),
                    claim.getReference(),
                    claim.getStatus().name(),
                    claim.getSubmittedAt());
        }
    }

    /** The operator's review queue row. Carries the order it belongs to. */
    public record AdminClaimResponse(
            Long id,
            String publicRef,
            String customerEmail,
            String sku,
            long totalMinor,
            String totalFormatted,
            String currency,
            String method,
            /** Which account the customer was told to pay. Where to go looking. */
            String destination,
            String reference,
            String status,
            Instant submittedAt,
            Instant reviewedAt,
            String reviewNote) {
    }

    public record ReviewClaimRequest(
            @Size(max = 500)
            String note) {
    }
}
