package com.globalfutservice.orders.web;

import com.globalfutservice.pricing.web.QuoteDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record CreateOrderRequest(
            /** The signed quote, posted back exactly as it was received. */
            @Valid @NotNull QuoteDtos.SignedQuote quote,

            @Email(message = "We need a working email to send your receipt")
            @NotBlank(message = "Email is required")
            @Size(max = 255)
            String email,

            @Size(max = 20)
            @Pattern(regexp = "^[+0-9 ()-]*$", message = "That does not look like a phone number")
            String phone,

            /** PLAYER_AUCTION or COMFORT_TRADE. Defaults to the safer of the two. */
            String deliveryMethod,

            @Size(max = 64, message = "That handle is too long")
            String eaPlatformHandle,

            @Size(max = 500)
            String note,

            @AssertTrue(message = "Please accept the terms to place your order")
            boolean acceptedTerms) {
    }

    /** Everything the browser needs to open the payment sheet. */
    public record CreateOrderResponse(
            String publicRef,
            String status,
            long totalMinor,
            String totalFormatted,
            String currency,
            PaymentIntent payment) {
    }

    public record PaymentIntent(
            String provider,
            String providerOrderId,
            String publicKey,
            long amountMinor,
            String currency,
            String customerEmail,
            String description) {
    }

    public record OrderLineDto(String code, String label, long amountMinor, String amountFormatted) {
    }

    public record OrderEventDto(
            String fromStatus,
            String toStatus,
            String actorType,
            String actorLabel,
            String reason,
            Instant at) {
    }

    /**
     * The customer's view of an order.
     *
     * <p>{@code nextAction} is computed server-side rather than inferred in the UI. The
     * frontend should not be reimplementing the state machine to decide whether to show a
     * "submit your sign-in" form — that is exactly how the two drift apart.
     */
    public record OrderResponse(
            String publicRef,
            String status,
            String statusLabel,
            String nextAction,
            String serviceLabel,
            String sku,
            String platform,
            String variant,
            BigDecimal quantity,
            String deliveryMethod,
            boolean credentialsRequired,
            boolean credentialsSubmitted,
            String currency,
            long totalMinor,
            String totalFormatted,
            List<OrderLineDto> lines,
            long pointsRedeemed,
            long pointsEarned,
            String referralCode,

            /*
              Live fulfilment, straight from the supplier.

              `deliveredCoins` / `orderedCoins` are what turn the tracking page from a
              spinner into a number that moves — the supplier reports them on every poll
              while an order is partly delivered, and a progress bar the customer can
              watch is most of the reason to poll onto our own page rather than link out.

              `customerAction` is the other half: most of the supplier's stall codes name
              something the customer can fix in under a minute. Sent as the enum name and
              translated in the browser, so the wording is a copy change rather than a
              deploy.
            */
            Long deliveredCoins,
            Long orderedCoins,
            String customerAction,
            Instant createdAt,
            Instant deliveredAt,
            Instant guaranteeExpiresAt,
            List<OrderEventDto> timeline) {
    }

    /**
     * Guest order lookup.
     *
     * <p>Requires the email as well as the reference. A reference alone would make order
     * details enumerable by anyone who found one — and references appear in email
     * subjects, screenshots and support chats.
     */
    public record TrackOrderRequest(
            @NotBlank(message = "Order reference is required")
            @Size(max = 32)
            String publicRef,

            @Email(message = "That does not look like an email address")
            @NotBlank(message = "Email is required")
            String email) {
    }

    /** Admin queue row — deliberately lighter than the full order view. */
    public record AdminOrderSummary(
            String publicRef,
            String status,
            String serviceLabel,
            String platform,
            BigDecimal quantity,
            String deliveryMethod,
            boolean credentialsHeld,
            String customerEmail,
            long totalMinor,
            String totalFormatted,
            String currency,
            Instant createdAt,
            Instant deliveredAt,
            List<String> availableTransitions) {
    }

    public record TransitionRequest(
            @NotBlank(message = "A target status is required")
            String toStatus,
            @Size(max = 500)
            String reason) {
    }

    public record AdminStats(
            long awaitingPayment,
            long paid,
            long credentialsPending,
            long readyForDelivery,
            long inProgress,
            long onHold,
            long deliveredAwaitingGuarantee,
            long disputed,
            long credentialsHeld,
            long revenueLast30dMinor,
            String revenueLast30dFormatted) {
    }
}
