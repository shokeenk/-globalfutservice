package com.globalfutservice.pricing;

import com.globalfutservice.affiliate.AffiliateService;
import com.globalfutservice.catalog.CatalogService;
import com.globalfutservice.catalog.RateCardEntity;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.pricing.CustomerPricingContext;
import com.globalfutservice.domain.pricing.LineCode;
import com.globalfutservice.domain.pricing.PricingEngine;
import com.globalfutservice.domain.pricing.Quote;
import com.globalfutservice.domain.pricing.QuoteLine;
import com.globalfutservice.domain.pricing.QuoteSigner;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.loyalty.LoyaltyService;
import com.globalfutservice.pricing.web.QuoteDtos;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.web.ApiExceptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Mints prices.
 *
 * <p>The one job of this class is to make sure the buyer context that reaches the pricing
 * engine came from the database and not from the request body. A client that can assert
 * its own wallet balance, its own first-order status, or its own discount rate is a
 * client that can buy for nothing.
 */
@Service
public class QuoteService {

    private final CatalogService catalogService;
    private final PricingEngine engine;
    private final QuoteSigner signer;
    private final LoyaltyService loyaltyService;
    private final AffiliateService affiliateService;
    private final CouponService couponService;
    private final Clock clock;

    public QuoteService(CatalogService catalogService, PricingEngine engine, QuoteSigner signer,
                        LoyaltyService loyaltyService, AffiliateService affiliateService,
                        CouponService couponService, Clock clock) {
        this.catalogService = catalogService;
        this.engine = engine;
        this.signer = signer;
        this.loyaltyService = loyaltyService;
        this.affiliateService = affiliateService;
        this.couponService = couponService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public QuoteDtos.SignedQuote quote(QuoteDtos.QuoteRequest request, AccountEntity account) {
        Sku sku = parseEnum(Sku.class, request.sku(), "Unknown service.");
        Platform platform = request.platform() == null || request.platform().isBlank()
                ? null : parseEnum(Platform.class, request.platform(), "Unknown platform.");
        Currency currency = request.currency() == null || request.currency().isBlank()
                ? Currency.INR : parseEnum(Currency.class, request.currency(), "Unknown currency.");

        RateCardEntity rate = catalogService.requireLiveRate(sku, platform, request.variant(), currency);
        Long accountId = account == null ? null : account.getId();

        /*
         * Priced twice when a coupon is supplied, and deliberately so.
         *
         * A coupon can carry a minimum order value, which needs the subtotal — but the
         * subtotal comes out of the engine. The alternative to a provisional pass is
         * recomputing "base plus market tax" here, which is a second implementation of
         * the one formula that must never disagree with itself. The engine is pure
         * arithmetic over value objects, so running it twice costs microseconds, and the
         * subtotal is unaffected by any discount, so the second pass cannot move it.
         */
        Quote provisional = engine.quote(
                rate.toDomain(), request.quantity(), contextFor(account, request, null));

        Optional<CouponService.Resolved> coupon =
                couponService.resolve(request.couponCode(), accountId, provisional.subtotal());

        Quote quote = coupon
                .map(c -> engine.quote(rate.toDomain(), request.quantity(),
                        contextFor(account, request, c)))
                .orElse(provisional);

        // Only explain a code that was supplied and did not apply. Explaining one that
        // worked would put "That code is not valid" next to a discount that plainly is.
        String message = coupon.isPresent()
                ? null
                : couponService.explain(request.couponCode(), accountId, provisional.subtotal())
                        .orElse(null);

        return toDto(quote, signer.sign(quote, binding(account)), message);
    }

    /**
     * Everything the engine is allowed to know about the buyer, assembled from
     * authoritative sources.
     */
    private CustomerPricingContext contextFor(AccountEntity account,
                                              QuoteDtos.QuoteRequest request,
                                              CouponService.Resolved coupon) {
        String couponCode = coupon == null ? null : coupon.code();
        int couponBps = coupon == null ? 0 : coupon.discountBps();
        if (account == null) {
            // Guest: no balance to spend, treated as a first order so a creator code
            // still discounts — that is the entire point of the code.
            int guestDiscount = affiliateService.firstOrderDiscountBps(request.referralCode());
            // No lifetime history either, so a guest always prices at BRONZE. Tier
            // discounts require an account by construction, not by a check somewhere.
            return new CustomerPricingContext(0L, 0L, 0L, true,
                    normaliseCode(request.referralCode()), guestDiscount,
                    couponCode, couponBps);
        }
        long balance = loyaltyService.balance(account.getId());
        long lifetime = loyaltyService.lifetimePoints(account.getId());
        // A returning customer keeps the code they signed up under unless they type a
        // different one at checkout.
        String code = request.referralCode() != null && !request.referralCode().isBlank()
                ? request.referralCode()
                : account.getReferredByCode();
        int discountBps = affiliateService.firstOrderDiscountBps(code);
        return new CustomerPricingContext(
                balance,
                request.pointsToRedeem() == null ? 0L : request.pointsToRedeem(),
                lifetime,
                account.isFirstOrder(),
                normaliseCode(code),
                discountBps,
                couponCode,
                couponBps);
    }

    /**
     * Rebuilds a domain quote from what the client posted back and checks the signature.
     *
     * <p>Returns the reconstructed quote only if the signature is intact and the quote has
     * not expired. Everything downstream can then treat it as a server-issued price.
     */
    public Quote verifyOrThrow(QuoteDtos.SignedQuote dto, AccountEntity account) {
        Quote quote = fromDto(dto);
        if (quote.isExpiredAt(clock.instant())) {
            throw new ApiExceptions.ConflictException("quote_expired",
                    "That price has expired. Refresh to get the current one.");
        }
        if (!signer.verify(quote, binding(account), dto.signature(), clock.instant())) {
            // Deliberately identical wording to the expiry case. Telling a caller which
            // check failed tells them whether their forgery attempt is getting warmer.
            throw new ApiExceptions.ConflictException("quote_expired",
                    "That price has expired. Refresh to get the current one.");
        }
        if (!quote.linesReconcile()) {
            throw new ApiExceptions.ConflictException("quote_expired",
                    "That price has expired. Refresh to get the current one.");
        }
        return quote;
    }

    private static String binding(AccountEntity account) {
        return account == null ? "guest" : account.getPublicId();
    }

    private static String normaliseCode(String code) {
        return code == null || code.isBlank() ? null : code.trim().toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ mapping

    public QuoteDtos.SignedQuote toDto(Quote quote, String signature) {
        return toDto(quote, signature, null);
    }

    public QuoteDtos.SignedQuote toDto(Quote quote, String signature, String couponMessage) {
        List<QuoteDtos.QuoteLineDto> lines = quote.lines().stream()
                .map(l -> new QuoteDtos.QuoteLineDto(
                        l.code().name(), l.label(), l.amount().minor(), l.amount().format()))
                .toList();

        return new QuoteDtos.SignedQuote(
                quote.quoteId(),
                quote.season(),
                quote.sku().name(),
                quote.platform() == null ? null : quote.platform().name(),
                quote.variant(),
                quote.quantity(),
                quote.currency().name(),
                lines,
                quote.subtotal().minor(),
                quote.total().minor(),
                quote.total().format(),
                quote.pointsRedeemed(),
                quote.pointsEarned(),
                quote.referralCode(),
                quote.couponCode(),
                couponMessage,
                quote.issuedAt(),
                quote.expiresAt(),
                signature);
    }

    private Quote fromDto(QuoteDtos.SignedQuote dto) {
        try {
            Currency currency = Currency.valueOf(dto.currency());
            List<QuoteLine> lines = dto.lines().stream()
                    .map(l -> new QuoteLine(
                            LineCode.valueOf(l.code()), l.label(),
                            Money.ofMinor(l.amountMinor(), currency)))
                    .toList();
            return new Quote(
                    dto.quoteId(),
                    dto.season(),
                    Sku.valueOf(dto.sku()),
                    dto.platform() == null ? null : Platform.valueOf(dto.platform()),
                    dto.variant(),
                    dto.quantity() == null ? BigDecimal.ONE : dto.quantity(),
                    currency,
                    lines,
                    Money.ofMinor(dto.subtotalMinor(), currency),
                    Money.ofMinor(dto.totalMinor(), currency),
                    dto.pointsRedeemed(),
                    dto.pointsEarned(),
                    dto.referralCode(),
                    dto.couponCode(),
                    dto.issuedAt(),
                    dto.expiresAt());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiExceptions.BadRequestException("malformed_quote",
                    "That price could not be read. Please refresh and try again.");
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String message) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiExceptions.BadRequestException(message);
        }
    }
}
