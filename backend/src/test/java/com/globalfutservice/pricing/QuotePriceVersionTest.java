package com.globalfutservice.pricing;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;

import com.globalfutservice.catalog.CatalogService;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.RateCard;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.crypto.Hmac;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.pricing.CustomerPricingContext;
import com.globalfutservice.domain.pricing.GatewayFeeMode;
import com.globalfutservice.domain.pricing.MarketTaxMode;
import com.globalfutservice.domain.pricing.PricingEngine;
import com.globalfutservice.domain.pricing.PricingPolicy;
import com.globalfutservice.domain.pricing.Quote;
import com.globalfutservice.domain.pricing.QuoteSigner;
import com.globalfutservice.loyalty.LoyaltyService;
import com.globalfutservice.affiliate.AffiliateService;
import com.globalfutservice.pricing.web.QuoteDtos;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * A coin quote carries the price version that priced it, signed, so the order it becomes
 * can be explained against that version. Quotes without one -- boosting, coaching, and a
 * coin quote from before versions -- are signed exactly as they always were.
 */
class QuotePriceVersionTest {

    private static final String SECRET = "test-only-quote-secret-00000000000000000000000000000";
    private final QuoteSigner signer = new QuoteSigner(SECRET);
    private final PricingEngine engine = new PricingEngine(new PricingPolicy(500, MarketTaxMode.INCLUDED, 250,
            GatewayFeeMode.PASS_THROUGH, 2_000, Currency.INR, 100L, 200_000L, 20L, Duration.ofMinutes(10), true),
            Clock.systemUTC(), () -> "q_test0001");
    private final QuoteService quotes = new QuoteService(mock(CatalogService.class), engine, signer,
            mock(LoyaltyService.class), mock(AffiliateService.class), mock(CouponService.class), Clock.systemUTC());

    private Quote coins(Long version) {
        RateCard card = new RateCard("FC26", Sku.TRADING_SERVICE, Platform.PLAYSTATION, null, "PlayStation",
                Money.ofMinor(1_300_000, Currency.INR), new BigDecimal("0.05"), new BigDecimal("1.00"),
                new BigDecimal("0.01"), version);
        return engine.quote(card, new BigDecimal("0.10"), CustomerPricingContext.guest());
    }

    /** The canonical form as it was before price versions, written out longhand. */
    private static String v1(Quote q) {
        return String.join("|", "v1", q.quoteId(), q.season(), q.sku().name(),
                q.platform() == null ? "-" : q.platform().name(), q.variant() == null ? "-" : q.variant(),
                q.quantity().stripTrailingZeros().toPlainString(), q.currency().name(),
                String.valueOf(q.total().minor()), String.valueOf(q.pointsRedeemed()), String.valueOf(q.pointsEarned()),
                "-", "-", String.valueOf(q.expiresAt().toEpochMilli()), "guest");
    }

    @Test
    @DisplayName("no version: signed exactly as before, so nothing in flight across the deploy breaks")
    void unversionedUnchanged() {
        Quote q = coins(null);
        assertThat(q.priceVersion()).isNull();
        assertThat(signer.sign(q, "guest")).isEqualTo(Hmac.base64UrlSha256(SECRET, v1(q)));
    }

    @Test
    @DisplayName("a version is signed, and removing or changing it fails the check")
    void versionSigned() {
        Quote q = coins(42L);
        String signature = signer.sign(q, "guest");
        assertThat(signature).isNotEqualTo(Hmac.base64UrlSha256(SECRET, v1(q)));
        assertThat(signer.verify(q, "guest", signature, q.issuedAt())).isTrue();
        assertThat(signer.verify(withVersion(q, null), "guest", signature, q.issuedAt())).isFalse();
        assertThat(signer.verify(withVersion(q, 43L), "guest", signature, q.issuedAt())).isFalse();
    }

    @Test
    @DisplayName("through the storefront and back: the version survives, and is what the order is frozen with")
    void roundTrip() {
        Quote q = coins(42L);
        QuoteDtos.SignedQuote dto = quotes.toDto(q, signer.sign(q, "guest"));
        assertThat(dto.priceVersion()).isEqualTo(42L);
        assertThat(quotes.verifyOrThrow(dto, null).priceVersion()).isEqualTo(42L);

        QuoteDtos.SignedQuote stripped = new QuoteDtos.SignedQuote(dto.quoteId(), dto.season(), dto.sku(),
                dto.platform(), dto.variant(), dto.quantity(), dto.currency(), dto.lines(), dto.subtotalMinor(),
                dto.totalMinor(), dto.totalFormatted(), dto.pointsRedeemed(), dto.pointsEarned(), dto.referralCode(),
                dto.couponCode(), dto.couponMessage(), dto.issuedAt(), dto.expiresAt(), dto.signature(), null);
        assertThatThrownBy(() -> quotes.verifyOrThrow(stripped, null))
                .isInstanceOf(ApiExceptions.ConflictException.class);
    }

    private static Quote withVersion(Quote q, Long version) {
        return new Quote(q.quoteId(), q.season(), q.sku(), q.platform(), q.variant(), q.quantity(), q.currency(),
                q.lines(), q.subtotal(), q.total(), q.pointsRedeemed(), q.pointsEarned(), q.referralCode(),
                q.couponCode(), q.issuedAt(), q.expiresAt(), version);
    }
}
