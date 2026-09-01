package com.globalfutservice.pricing.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class QuoteDtos {

    private QuoteDtos() {
    }

    /**
     * What the configurator sends.
     *
     * <p>Note what is <b>absent</b>: any price, any discount amount, any wallet balance.
     * The client states intent and nothing else. Everything that determines what the
     * customer pays is read server-side, which is the difference between a storefront and
     * a suggestion box.
     */
    public record QuoteRequest(
            @NotBlank String sku,
            String platform,
            String variant,

            @DecimalMin(value = "0.0", inclusive = false, message = "Choose an amount")
            @DecimalMax(value = "1000.0", message = "That is larger than we can deliver in one order")
            BigDecimal quantity,

            @Pattern(regexp = "^[A-Z]{3}$", message = "Unknown currency")
            String currency,

            @Size(max = 32, message = "That code is too long")
            @Pattern(regexp = "^[A-Za-z0-9_-]*$", message = "Codes are letters and numbers only")
            String referralCode,

            @Size(max = 32, message = "That code is too long")
            @Pattern(regexp = "^[A-Za-z0-9_-]*$", message = "Codes are letters and numbers only")
            String couponCode,

            @PositiveOrZero(message = "Points cannot be negative")
            Long pointsToRedeem) {
    }

    public record QuoteLineDto(String code, String label, long amountMinor, String amountFormatted) {
    }

    /**
     * A price the server will honour until {@code expiresAt}.
     *
     * <p>The client posts this object back verbatim, signature included, to create an
     * order. Every field that moves money is covered by the signature, so the round trip
     * through the browser is safe; a single altered digit invalidates it.
     */
    public record SignedQuote(
            @NotBlank String quoteId,
            @NotBlank String season,
            @NotBlank String sku,
            String platform,
            String variant,
            @NotNull BigDecimal quantity,
            @NotBlank String currency,
            @NotNull List<QuoteLineDto> lines,
            long subtotalMinor,
            long totalMinor,
            String totalFormatted,
            long pointsRedeemed,
            long pointsEarned,
            String referralCode,
            /** The coupon that was applied, or null. Part of the signed payload. */
            String couponCode,
            /**
             * Why a supplied coupon did not apply, or null.
             *
             * <p>Carried alongside the price rather than raised as an error: a quote is
             * recomputed on every slider drag, and turning a spent code into a failed
             * request would make the price vanish instead of merely losing its discount.
             */
            String couponMessage,
            @NotNull Instant issuedAt,
            @NotNull Instant expiresAt,
            @NotBlank String signature) {
    }
}
