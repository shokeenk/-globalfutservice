package com.globalfutservice.config;

import com.globalfutservice.domain.orders.DeliveryMethod;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.pricing.GatewayFeeMode;
import com.globalfutservice.domain.pricing.MarketTaxMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

/**
 * Every knob in the application, bound once at startup and validated before the context
 * finishes refreshing.
 *
 * <p>Secrets are declared here but never given defaults. A missing signing key must stop
 * the application from starting, not silently fall back to a development constant that
 * then ships to production — which is the single most common way a JWT secret ends up
 * being "changeit".
 */
@ConfigurationProperties(prefix = "gfs")
@Validated
public record AppProperties(

        /** Current EA title. Drives rate-card lookup, page copy and meta tags. */
        @DefaultValue("FC26") String season,

        /** Two-digit season year used in customer-facing order references. */
        @DefaultValue("26") String seasonYear,

        /** Public origin of the storefront, used to build links in emails. */
        @DefaultValue("http://localhost:5173") String publicUrl,

        @Valid @NotNull Security security,
        @Valid @NotNull Pricing pricing,
        @Valid @NotNull Fulfilment fulfilment,
        @Valid @NotNull Boosting boosting,
        @Valid @NotNull Razorpay razorpay,
        @Valid @NotNull Notifications notifications,
        @Valid @NotNull RateLimit rateLimit,
        @Valid @NotNull Loyalty loyalty,
        @Valid @NotNull Coaching coaching,
        @Valid @NotNull ManualPayments manualPayments,
        @Valid @NotNull FutTransfer futTransfer) {

    public record Security(
            /** HS256 signing key for access tokens. Minimum 32 bytes. */
            @NotBlank String jwtSecret,
            @DefaultValue("15m") Duration accessTokenTtl,
            @DefaultValue("30d") Duration refreshTokenTtl,

            /** Separate secret for quote signatures — never reuse the JWT key. */
            @NotBlank String quoteSigningSecret,

            /** Base64 of exactly 32 bytes. Generate with: openssl rand -base64 32 */
            @NotBlank String credentialMasterKey,

            /** Exact origins allowed to call the API. Never "*" with credentials. */
            @DefaultValue("http://localhost:5173") List<String> corsAllowedOrigins,

            /** Set both to have an operator account created on first boot. */
            String bootstrapAdminEmail,
            String bootstrapAdminPassword,

            /** Emit HSTS and force secure cookies. Enable in every deployed environment. */
            @DefaultValue("false") boolean requireHttps,

            @DefaultValue("5") int maxFailedLogins,
            @DefaultValue("15m") Duration loginLockout) {
    }

    public record Pricing(
            @DefaultValue("500") int marketTaxBps,
            @DefaultValue("250") int gatewayFeeBps,
            /*
             * INCLUDED by default: the listed per-million price already covers EA's cut,
             * which is what the storefront has always told customers. ADDED restores the
             * old behaviour of charging it as a separate line.
             */
            @DefaultValue("INCLUDED") MarketTaxMode marketTaxMode,
            @DefaultValue("PASS_THROUGH") GatewayFeeMode gatewayFeeMode,
            @DefaultValue("2000") int maxWalletRedemptionBps,
            /**
             * The currency the loyalty numbers below are denominated in.
             *
             * <p>Orders in any other currency neither earn nor redeem points. Changing
             * this without also restating {@code pointValueMinor} and
             * {@code earnSpendUnitMinor} in the new currency would silently reprice every
             * balance already issued, so treat the three as one setting.
             */
            @DefaultValue("INR") Currency loyaltyCurrency,
            @DefaultValue("100") long pointValueMinor,
            @DefaultValue("200000") long earnSpendUnitMinor,
            @DefaultValue("20") long earnPointsPerUnit,
            @DefaultValue("10m") Duration quoteTtl,
            /**
             * Whether the six-tier loyalty ladder discounts orders automatically.
             *
             * <p>A switch because it stacks with the points rebate: a DIAMOND customer
             * redeeming their balance costs roughly 6% of gross between the two. That is a
             * margin decision the owner may need to reverse quickly, and it should not
             * require a redeploy of pricing logic to do it.
             */
            @DefaultValue("true") boolean tierDiscountEnabled,
            /** Currencies offered to the storefront, subject to live rate-card rows. */
            @DefaultValue("INR") List<String> enabledCurrencies) {
    }

    public record Loyalty(
            /** Points granted for a daily check-in. Zero disables the feature entirely. */
            @DefaultValue("3") long dailyBonusPoints,

            /**
             * The zone whose midnight ends a loyalty day.
             *
             * <p>Not UTC. With a UTC boundary an Indian customer's day rolls over at
             * 05:30 local, so two check-ins the same evening-and-morning count as two days
             * while two at 9am on consecutive days count as one. The customer's intuition
             * about "today" is the one the streak has to match.
             */
            @DefaultValue("Asia/Kolkata") ZoneId bonusZone) {
    }

    public record Coaching(
            /**
             * How long a single purchased session runs.
             *
             * <p>Sessions already booked keep the length they were booked at: the value
             * is copied onto the row at creation rather than read back from config, so
             * changing it moves future bookings only.
             */
            @DefaultValue("60m") Duration sessionLength,

            /**
             * How long one session from a multi-session block runs.
             *
             * <p><b>There are two lengths because there are two products.</b> A single
             * session is an hour at Rs.1,000. The six-session block is Rs.4,050, which is
             * Rs.675 a session, and it is cheaper because each session is shorter — the
             * duration difference is the reason for the price difference, not a detail
             * beside it.
             *
             * <p>The application could not express that until now: one global
             * {@code sessionLength} drove every booking, so a block customer and a
             * single-session customer picking the same slot got sessions of identical
             * length. That is not a labelling problem. It gave away twenty minutes a
             * session against what the block was sold as, and it over-allocated the
             * coach's calendar by half an hour for every block booking.
             *
             * <p>Which of the two applies is decided by the credit being spent, not by
             * configuration — see {@code CoachingService.sessionLengthFor}.
             */
            @DefaultValue("40m") Duration blockSessionLength,
            @DefaultValue("30m") Duration slotStep,
            @DefaultValue("2h") Duration minLeadTime,
            @DefaultValue("60d") Duration maxAdvance,

            /** Notice required to move or cancel without losing the session credit. */
            @DefaultValue("12h") Duration changeCutoff,
            @DefaultValue("2") int maxReschedules,
            @DefaultValue("15m") Duration noShowGrace,

            /** How long session credits stay usable after purchase. */
            @DefaultValue("30d") Duration creditValidity,

            /**
             * Sessions granted per rate-card variant.
             *
             * <p>Keyed by variant so a new pack — a twelve-session block, a trial pair — is
             * a rate-card row plus one line of configuration, not a code change. The
             * pricing side of the same decision already lives in the rate card; this is the
             * fulfilment side of it, and the two are deliberately adjacent.
             */
            @DefaultValue({"SINGLE_SESSION:1", "MONTHLY_6_SESSIONS:6"})
            List<String> creditsPerVariant) {

        /**
         * How long a session bought under this variant runs.
         *
         * <p>Any variant granting more than one session is a block, which is what makes
         * this derive from {@code creditsFor} rather than carry a second mapping to be
         * kept in step with it. A future "10 sessions" pack is a block on the day it is
         * priced, with nothing here to remember to update.
         */
        public Duration sessionLengthFor(String variant) {
            return creditsFor(variant) > 1 ? blockSessionLength : sessionLength;
        }

        /** Sessions granted by a variant, or 0 when the variant grants none. */
        public int creditsFor(String variant) {
            if (variant == null) {
                return 0;
            }
            for (String mapping : creditsPerVariant) {
                int sep = mapping.lastIndexOf(':');
                if (sep > 0 && mapping.substring(0, sep).trim().equalsIgnoreCase(variant.trim())) {
                    try {
                        return Integer.parseInt(mapping.substring(sep + 1).trim());
                    } catch (NumberFormatException malformed) {
                        return 0;
                    }
                }
            }
            return 0;
        }
    }

    /**
     * What the boosting page is allowed to claim about outcomes.
     *
     * <p><b>These are supplied by the business, not measured by this application.</b>
     * Nothing here records what rank an order actually reached -- {@code OrderStatus} runs
     * to DELIVERED and COMPLETED, which say an order finished, never what it finished at --
     * so there is no achieved-versus-ordered figure to aggregate and this cannot be
     * computed. It is configuration precisely because of that: a number the owner stands
     * behind, changeable or withdrawable without a deploy, and obviously an input rather
     * than a result to anyone reading the code.
     */
    public record Boosting(
            /** {@code VARIANT:basisPoints}, e.g. {@code WINS_15:9500} for 95%. */
            @DefaultValue({}) List<String> successRatePerVariant) {

        /** Published rate for a variant in basis points, or null when none is set. */
        public Integer successRateBpsFor(String variant) {
            if (variant == null) {
                return null;
            }
            for (String mapping : successRatePerVariant) {
                int sep = mapping.lastIndexOf(':');
                if (sep > 0 && mapping.substring(0, sep).trim().equalsIgnoreCase(variant.trim())) {
                    try {
                        int bps = Integer.parseInt(mapping.substring(sep + 1).trim());
                        // Out of range is dropped rather than clamped: a typo turning 95%
                        // into 950% should publish nothing, not something.
                        return (bps > 0 && bps <= 10_000) ? bps : null;
                    } catch (NumberFormatException malformed) {
                        return null;
                    }
                }
            }
            return null;
        }
    }

    public record Fulfilment(
            /** Post-delivery window in which a ban or coin removal is covered. */
            @DefaultValue("7d") Duration guaranteeWindow,

            /** How long after delivery an EA sign-in may remain in the vault. */
            @DefaultValue("24h") Duration credentialRetention,

            /** Contractual outer bound quoted in the Terms — deliberately far looser
             *  than the marketing promise, so the business under-promises in writing. */
            @DefaultValue("48h") Duration deliverySla,

            /** Player auction needs no credentials, so it is the safe default. */
            @DefaultValue("PLAYER_AUCTION") DeliveryMethod defaultDeliveryMethod,

            /** Deducted from cash refunds, matching the published Terms. */
            @DefaultValue("500") int refundFeeBps,

            /** Store credit offered instead of cash on an upheld guarantee claim.
             *  10000 bps = 100%, against 50% in cash — the asymmetry is deliberate. */
            @DefaultValue("10000") int guaranteeCreditBps,
            @DefaultValue("5000") int guaranteeCashBps) {
    }

    public record Razorpay(
            @DefaultValue("false") boolean enabled,
            String keyId,
            String keySecret,
            String webhookSecret,
            @DefaultValue("https://api.razorpay.com/v1") String apiBaseUrl,
            @DefaultValue("10s") Duration timeout) {
    }

    /**
     * The FUT Transfer supplier that actually moves the coins.
     *
     * <p>{@code apiKey} is the <em>raw</em> key exactly as the supplier issued it. Their
     * API wants an MD5 of it in every request body, and that digest is computed at the
     * client rather than stored — so the value a human pastes into a hosting dashboard is
     * the value the supplier gave them, and nobody has to know that MD5 is involved.
     *
     * <p><b>riskLevel is a commercial setting, not a throughput one.</b> The supplier
     * documents 3 through 6 as escalating ban risk with <em>no refunds</em>, and this
     * storefront sells a seven-day ban guarantee on every order. Above 2, that guarantee
     * is being written against a supplier who has declined to back it. 2 is their default
     * and the only value consistent with what the site promises.
     */
    /**
     * Where customers are told to send money when they pay outside the gateway.
     *
     * <p>These live here, and not in the storefront bundle, for one reason: the server
     * records on every claim which address the customer was sent to, and a value the
     * browser supplied would be worth nothing as a record. The storefront reads them
     * back from {@code GET /api/v1/payments/methods} so there is exactly one copy.
     *
     * <p>Changing one is a live change to where customers' money goes. They are
     * deliberately environment variables rather than committed constants, so a handle
     * can be rotated without a deploy -- and so that a wrong value is an operations
     * incident with an audit trail rather than a merged pull request.
     */
    public record ManualPayments(

            /** Shown on coin and trading orders. A UPI VPA, e.g. {@code name@bank}. */
            String upiCoins,

            /** Name on the account behind {@link #upiCoins}, shown under the QR. */
            String upiCoinsName,

            /** Shown on boosting and coaching orders. */
            String upiServices,

            /** Name on the account behind {@link #upiServices}. */
            String upiServicesName,

            /** PayPal link for international customers. */
            String paypal,

            /** USDT wallet. TRON/TRC20 only -- see the warning the storefront shows. */
            String cryptoTrc20) {

        /**
         * The UPI destination for a sku, or null when UPI is not configured.
         *
         * <p>Coins go to one account and everything else to another, which is a fact
         * about how this business banks rather than anything the customer chooses. The
         * storefront shows one or the other for that reason, and this method is what
         * makes the server agree with it instead of trusting it.
         */
        public String upiFor(String sku) {
            boolean coins = "TRADING_SERVICE".equalsIgnoreCase(sku);
            return blankToNull(coins ? upiCoins : upiServices);
        }

        public String upiNameFor(String sku) {
            boolean coins = "TRADING_SERVICE".equalsIgnoreCase(sku);
            return blankToNull(coins ? upiCoinsName : upiServicesName);
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }

    public record FutTransfer(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("https://futtransfer.top") String baseUrl,
            /** Account email, sent as {@code apiUser}. */
            String apiUser,
            /** Raw API key. Hashed to MD5 per request; never logged. */
            String apiKey,
            /** snipe | cycle | targetedSnipe | snipeLimited */
            @DefaultValue("snipe") String transferMethod,
            /** 1 = maximum safety … 6 = ban mode. See the note above before raising it. */
            @DefaultValue("2") int riskLevel,
            @DefaultValue("60s") Duration pollInterval,
            @DefaultValue("15s") Duration timeout,
            /** Consecutive dispatch failures before an order is parked for an operator. */
            @DefaultValue("3") int maxDispatchAttempts) {

        public boolean isConfigured() {
            return enabled
                    && apiUser != null && !apiUser.isBlank()
                    && apiKey != null && !apiKey.isBlank();
        }
    }

    public record Notifications(
            @DefaultValue("false") boolean whatsappEnabled,
            String whatsappPhoneNumberId,
            String whatsappAccessToken,
            /** Operator's number in E.164, e.g. 919812345678. */
            String whatsappOperatorNumber,
            @DefaultValue("gfs_new_order") String whatsappTemplateName,
            @DefaultValue("https://graph.facebook.com/v20.0") String whatsappApiBaseUrl,

            /**
             * Telegram, the channel that works without Meta onboarding.
             *
             * <p>`telegramChatId` is the destination — a personal chat id for one
             * operator, or a negative group id if alerts should reach a team. Both
             * come from the bot's own getUpdates response after the target has sent
             * it one message; a bot cannot open a conversation first.
             */
            @DefaultValue("false") boolean telegramEnabled,
            String telegramBotToken,
            String telegramChatId,
            @DefaultValue("https://api.telegram.org") String telegramApiBaseUrl,

            @DefaultValue("false") boolean emailEnabled,
            /**
             * Who gets the order alerts. Comma-separated; blank disables the channel.
             *
             * <p>Separate from `emailFrom` because they answer different questions —
             * one is the address customers see, the other is the desk that has to go
             * and do the work.
             */
            String operatorEmails,
            @DefaultValue("orders@globalfutservices.com") String emailFrom,
            @DefaultValue("Global FUT Services") String emailFromName) {
    }

    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            /** Quotes are the scraping target — competitors price-match automatically. */
            @DefaultValue("30") int quotesPerMinute,
            /** Sign-in and registration: the credential-stuffing surface. */
            @DefaultValue("10") int authPerMinute,
            @DefaultValue("5") int orderCreationPerMinute,
            @DefaultValue("120") int defaultPerMinute) {
    }
}
