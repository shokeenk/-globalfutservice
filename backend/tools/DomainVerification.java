import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.RateCard;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.crypto.EnvelopeCipher;
import com.globalfutservice.domain.crypto.Hmac;
import com.globalfutservice.domain.coaching.AvailabilityRule;
import com.globalfutservice.domain.coaching.CoachingPolicy;
import com.globalfutservice.domain.coaching.IllegalSessionTransitionException;
import com.globalfutservice.domain.coaching.SessionStateMachine;
import com.globalfutservice.domain.coaching.SessionStatus;
import com.globalfutservice.domain.coaching.SlotPlanner;
import com.globalfutservice.domain.coaching.TimeRange;
import com.globalfutservice.domain.crypto.SecureIds;
import com.globalfutservice.domain.loyalty.LoyaltyTier;
import com.globalfutservice.domain.loyalty.PointsEntryType;
import com.globalfutservice.domain.loyalty.PointsWallet;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.orders.OrderStateMachine;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.domain.payments.RazorpaySignature;
import com.globalfutservice.domain.pricing.Coupon;
import com.globalfutservice.domain.pricing.CustomerPricingContext;
import com.globalfutservice.domain.pricing.GatewayFeeMode;
import com.globalfutservice.domain.pricing.MarketTaxMode;
import com.globalfutservice.domain.pricing.LineCode;
import com.globalfutservice.domain.pricing.PricingEngine;
import com.globalfutservice.domain.pricing.PricingException;
import com.globalfutservice.domain.pricing.PricingPolicy;
import com.globalfutservice.domain.pricing.Quote;
import com.globalfutservice.domain.pricing.QuoteLine;
import com.globalfutservice.domain.pricing.QuoteSigner;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Offline verification of the money-handling core.
 *
 * <p>Runs on a bare JDK with no Maven, no network and no database, so the arithmetic that
 * decides what a customer is charged can be checked on a laptop with no connectivity and
 * in CI before any dependency is downloaded. The same cases exist as JUnit tests under
 * src/test; this exists so that "does the pricing still add up?" is never blocked on a
 * dependency resolution failure.
 *
 * <p>Run with: ./verify-domain.sh
 */
public final class DomainVerification {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        money();
        rateCardValidation();
        pricingArithmetic();
        pricingDiscountsAndWallet();
        pricingRoundingReconciliation();
        gatewayFeeModes();
        loyaltyEarning();
        loyaltyCurrencyBoundary();
        quoteSigning();
        envelopeEncryption();
        orderStateMachine();
        razorpaySignatures();
        secureIdentifiers();
        pointsLedger();
        loyaltyTiers();
        tierDiscountPricing();
        coachingPolicy();
        coachingStateMachine();
        slotPlanning();
        coupons();

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("  PASS  " + passed + " assertions, 0 failures");
            System.exit(0);
        }
        System.out.println("  FAIL  " + passed + " passed, " + failures.size() + " failed:");
        failures.forEach(f -> System.out.println("        - " + f));
        System.exit(1);
    }

    // ------------------------------------------------------------------ sections

    private static void money() {
        section("Money");
        check("paise round-trip", Money.ofMajor("700.00", Currency.INR).minor() == 70_000L);
        check("half-up on construction", Money.ofMajor("0.005", Currency.INR).minor() == 1L);
        check("addition", Money.ofMinor(100, Currency.INR)
                .plus(Money.ofMinor(250, Currency.INR)).minor() == 350L);
        check("subtraction to negative", Money.ofMinor(100, Currency.INR)
                .minus(Money.ofMinor(250, Currency.INR)).minor() == -150L);
        check("indian grouping", Money.ofMajor("1234567.89", Currency.INR).format().equals("₹12,34,567.89"));
        check("western grouping", Money.ofMajor("1234567.89", Currency.USD).format().equals("$1,234,567.89"));
        check("small amount", Money.ofMajor("700", Currency.INR).format().equals("₹700.00"));
        check("negative formatting", Money.ofMajor("-250.50", Currency.INR).format().equals("-₹250.50"));
        checkThrows("cross-currency addition rejected", IllegalArgumentException.class,
                () -> Money.ofMinor(1, Currency.INR).plus(Money.ofMinor(1, Currency.USD)));
    }

    private static void rateCardValidation() {
        section("Rate card");
        RateCard pc = coinsRate(Platform.PC, "700.00");
        pc.validateQuantity(new BigDecimal("3.5"));
        check("valid quantity accepted", true);
        checkThrows("below minimum rejected", IllegalArgumentException.class,
                () -> pc.validateQuantity(new BigDecimal("0.2")));
        checkThrows("above maximum rejected", IllegalArgumentException.class,
                () -> pc.validateQuantity(new BigDecimal("500")));
        checkThrows("off-step rejected", IllegalArgumentException.class,
                () -> pc.validateQuantity(new BigDecimal("3.7")));
        checkThrows("negative rejected", IllegalArgumentException.class,
                () -> pc.validateQuantity(new BigDecimal("-5")));
    }

    /**
     * The launch defaults, but billing EA's cut on top.
     *
     * <p>Every expectation in the discount, tier and coupon sections below was computed by
     * hand against a subtotal that included the 5% tax. Those checks are about how
     * discounts compose, not about who pays EA — so they keep the old rule and stay
     * exactly as sharp as they were. The base-case section above is the one that tests
     * the shipped default.
     */
    private static PricingPolicy taxAddedPolicy() {
        PricingPolicy d = PricingPolicy.launchDefaults();
        return new PricingPolicy(d.marketTaxBps(), MarketTaxMode.ADDED, d.gatewayFeeBps(),
                d.gatewayFeeMode(), d.maxWalletRedemptionBps(), d.loyaltyCurrency(),
                d.pointValueMinor(), d.earnSpendUnitMinor(), d.earnPointsPerUnit(),
                d.quoteTtl(), d.tierDiscountEnabled());
    }

    private static void pricingArithmetic() {
        section("Pricing — base case");
        PricingEngine engine = engine(PricingPolicy.launchDefaults());
        Quote q = engine.quote(coinsRate(Platform.PC, "700.00"),
                new BigDecimal("3.5"), CustomerPricingContext.guest());

        // 3.5M x Rs.700 = Rs.2450.00 base
        //   EA market tax                 = Rs.    0.00   (inside the listed price)
        //   + 2.5% processing on Rs.2450.00 = Rs.   61.25 -> total    Rs.2511.25
        check("base line", line(q, LineCode.BASE).minor() == 245_000L);
        check("market tax shown at zero", line(q, LineCode.MARKET_TAX).minor() == 0L);
        check("market tax line still present",
                q.lines().stream().anyMatch(l -> l.code() == LineCode.MARKET_TAX));
        check("gateway fee line", line(q, LineCode.GATEWAY_FEE).minor() == 6_125L);
        check("total", q.total().minor() == 251_125L);
        check("total formats as Rs.2,511.25", q.total().format().equals("₹2,511.25"));
        check("lines reconcile to total", q.linesReconcile());
        check("no discount lines for a guest",
                q.lines().stream().noneMatch(l -> l.code() == LineCode.REFERRAL_DISCOUNT
                        || l.code() == LineCode.WALLET_REDEMPTION));
        check("subtotal recorded", q.subtotal().minor() == 245_000L);
        check("quote carries ttl", Duration.between(q.issuedAt(), q.expiresAt()).toMinutes() == 10L);

        Quote console = engine.quote(coinsRate(Platform.PLAYSTATION, "600.00"),
                new BigDecimal("1"), CustomerPricingContext.guest());
        // 60000 listed, EA's cut already inside it ; 2.5% = 1500 ; total 61500
        check("console rate applied", console.total().minor() == 61_500L);

        // CARDS, not COACHING: coaching became sellable when the booking system shipped.
        checkThrows("unsellable sku rejected", PricingException.class,
                () -> engine.quote(new RateCard("FC26", Sku.CARDS, null, "STARTER", "Starter pack",
                                Money.ofMajor("2500", Currency.INR), null, null, null),
                        BigDecimal.ONE, CustomerPricingContext.guest()));

        // And the other half of that change: coaching must now actually quote.
        Quote coaching = engine.quote(new RateCard("FC26", Sku.COACHING, null, "SINGLE_SESSION", "Single session · 40 min",
                        Money.ofMajor("900", Currency.INR), null, null, null),
                BigDecimal.ONE, CustomerPricingContext.guest());
        // No market tax on coaching — nothing is traded. 90000 + 2.5% = 92250.
        check("coaching quotes without market tax", coaching.total().minor() == 92_250L);
        check("coaching carries no market-tax line",
                coaching.lines().stream().noneMatch(l -> l.code() == LineCode.MARKET_TAX));

        // A flat SKU's invoice line reads the rate card's label, never the variant. The
        // variant is a database discriminator; a customer receipt saying
        // "Rivals Boosting — DIV_1_TO_ELITE" is a column shipped to the customer by
        // accident, and it reached production once already.
        String coachingLine = coaching.lines().stream()
                .filter(l -> l.code() == LineCode.BASE)
                .map(QuoteLine::label).findFirst().orElse("");
        check("flat sku line uses the human label",
                coachingLine.contains("Single session"));
        check("flat sku line never leaks the variant",
                !coachingLine.contains("SINGLE_SESSION"));

        // Unlabelled rows fall back to the variant — ugly, but never null on a receipt.
        Quote unlabelled = engine.quote(new RateCard("FC26", Sku.COACHING, null, "SINGLE_SESSION",
                        null, Money.ofMajor("900", Currency.INR), null, null, null),
                BigDecimal.ONE, CustomerPricingContext.guest());
        check("an unlabelled row falls back to its variant",
                unlabelled.lines().stream().filter(l -> l.code() == LineCode.BASE)
                        .anyMatch(l -> l.label().contains("SINGLE_SESSION")));
    }

    private static void pricingDiscountsAndWallet() {
        section("Pricing — discounts and wallet");
        PricingEngine engine = engine(taxAddedPolicy());
        RateCard pc = coinsRate(Platform.PC, "700.00");

        // First order with a 13% creator code.
        Quote referred = engine.quote(pc, new BigDecimal("3.5"),
                new CustomerPricingContext(0, 0, 0, true, "VINU", 1_300, null, 0));
        check("referral discount applied", line(referred, LineCode.REFERRAL_DISCOUNT).minor() == -33_443L);
        check("referral quote reconciles", referred.linesReconcile());
        check("referral cheaper than list", referred.total().minor() < 263_681L);

        // Same code, repeat customer: first-order-only, so no discount.
        Quote repeat = engine.quote(pc, new BigDecimal("3.5"),
                new CustomerPricingContext(0, 0, 0, false, "VINU", 1_300, null, 0));
        check("referral is first-order only", repeat.total().minor() == 263_681L);
        check("referral code not echoed when unused", repeat.referralCode() == null);

        // Wallet: subtotal Rs.2572.50, cap 20% = Rs.514.50 -> 514 points max.
        Quote capped = engine.quote(pc, new BigDecimal("3.5"),
                new CustomerPricingContext(10_000, 10_000, 0, false, null, 0, null, 0));
        check("wallet clamped to 20% cap", capped.pointsRedeemed() == 514L);
        check("wallet line is negative", line(capped, LineCode.WALLET_REDEMPTION).minor() == -51_400L);
        check("capped quote reconciles", capped.linesReconcile());

        // Wallet clamped by the actual balance, not the request.
        Quote thin = engine.quote(pc, new BigDecimal("3.5"),
                new CustomerPricingContext(50, 10_000, 0, false, null, 0, null, 0));
        check("wallet clamped to balance", thin.pointsRedeemed() == 50L);

        // Gateway fee is charged on the net, after discounts.
        long netAfterWallet = 257_250L - 51_400L;
        long expectedFee = Math.round(netAfterWallet * 0.025);
        check("gateway fee follows discounts",
                line(capped, LineCode.GATEWAY_FEE).minor() == expectedFee);

        Quote none = engine.quote(pc, new BigDecimal("3.5"),
                new CustomerPricingContext(0, 5_000, 0, false, null, 0, null, 0));
        check("zero balance redeems nothing", none.pointsRedeemed() == 0L);
    }

    private static void pricingRoundingReconciliation() {
        section("Pricing — rounding");
        PricingEngine engine = engine(taxAddedPolicy());
        // Sweep every legal quantity on both rate cards and assert the invariant that
        // matters most: the printed breakdown always adds up to the amount charged.
        int checked = 0;
        boolean allReconcile = true;
        boolean allNonNegative = true;
        for (String rate : new String[]{"700.00", "600.00", "699.99", "633.33"}) {
            RateCard card = coinsRate(Platform.PC, rate);
            for (BigDecimal q = new BigDecimal("0.5");
                 q.compareTo(new BigDecimal("100")) <= 0;
                 q = q.add(new BigDecimal("0.5"))) {
                // Doubles as the lifetime total, so the sweep walks every rung of the tier
                // ladder — the residual-folding contract has to hold with a TIER_DISCOUNT
                // line present, not merely without one.
                for (long points : new long[]{0, 1, 500, 2_000, 5_000, 8_000, 10_000, 100_000}) {
                    Quote quote = engine.quote(card, q,
                            new CustomerPricingContext(points, points, points, points % 2 == 0,
                                    "VINU", 1_300,
                                    // A coupon on every third pass, at the ceiling. The
                                    // residual-folding contract has to hold with a
                                    // COUPON_DISCOUNT line present too, and this also
                                    // walks the coupon-beats-referral exclusion.
                                    points % 3 == 0 ? "SAVE20" : null,
                                    points % 3 == 0 ? 2_000 : 0));
                    allReconcile &= quote.linesReconcile();
                    allNonNegative &= quote.total().minor() >= 0;
                    checked++;
                }
            }
        }
        check("breakdown reconciles across " + checked + " quotes", allReconcile);
        check("no quote ever goes negative", allNonNegative);
    }

    private static void gatewayFeeModes() {
        section("Pricing — gateway fee modes");
        RateCard pc = coinsRate(Platform.PC, "700.00");

        PricingPolicy absorbed = new PricingPolicy(500, MarketTaxMode.ADDED, 250, GatewayFeeMode.ABSORBED,
                2_000, Currency.INR, 100L, 200_000L, 20L, Duration.ofMinutes(10), true);
        Quote a = engine(absorbed).quote(pc, new BigDecimal("1"), CustomerPricingContext.guest());
        check("absorbed mode hides the fee",
                a.lines().stream().noneMatch(l -> l.code() == LineCode.GATEWAY_FEE));
        check("absorbed total is the subtotal", a.total().minor() == 73_500L);

        PricingPolicy grossUp = new PricingPolicy(500, MarketTaxMode.ADDED, 250, GatewayFeeMode.GROSS_UP,
                2_000, Currency.INR, 100L, 200_000L, 20L, Duration.ofMinutes(10), true);
        Quote g = engine(grossUp).quote(pc, new BigDecimal("1"), CustomerPricingContext.guest());
        // 73500 / 0.975 = 75384.6154 -> fee 1884.6154 -> total 75385
        check("gross-up nets the merchant whole", g.total().minor() == 75_385L);
        check("gross-up exceeds pass-through", g.total().minor() > 75_338L);
        check("gross-up reconciles", g.linesReconcile());
    }

    private static void loyaltyEarning() {
        section("Loyalty — earning");
        PricingEngine engine = engine(taxAddedPolicy());
        check("Rs.1,999 earns nothing", engine.pointsEarnedOn(Money.ofMajor("1999", Currency.INR)) == 0L);
        check("Rs.2,000 earns 20", engine.pointsEarnedOn(Money.ofMajor("2000", Currency.INR)) == 20L);
        check("Rs.3,999 still earns 20", engine.pointsEarnedOn(Money.ofMajor("3999", Currency.INR)) == 20L);
        check("Rs.10,000 earns 100", engine.pointsEarnedOn(Money.ofMajor("10000", Currency.INR)) == 100L);
        check("zero earns nothing", engine.pointsEarnedOn(Money.zero(Currency.INR)) == 0L);
        check("effective rebate is 1%",
                engine.pointsEarnedOn(Money.ofMajor("2000", Currency.INR)) * 100L == 2_000L);
    }

    /**
     * The currency boundary on the loyalty programme.
     *
     * <p>These assertions exist because the parameters used to be bare scalars. A
     * $2,000 order matched the same 200,000-minor threshold as a ₹2,000 one and paid
     * out the same 20 points; those points then redeemed at 100 minor units each,
     * which is one dollar rather than one rupee. One shared balance, two wildly
     * different values, and nothing in the type system to notice.
     *
     * <p>The fix is a denomination on the policy, and these are the assertions that
     * would fail if anyone removed it.
     */
    private static void loyaltyCurrencyBoundary() {
        section("Loyalty — currency boundary");
        PricingEngine engine = engine(taxAddedPolicy());

        check("the programme settles in INR",
                PricingPolicy.launchDefaults().loyaltyCurrency() == Currency.INR);
        check("INR earns", PricingPolicy.launchDefaults().earnsLoyalty(Currency.INR));
        check("USD does not", !PricingPolicy.launchDefaults().earnsLoyalty(Currency.USD));

        // Earning: identical minor amounts, opposite outcomes.
        check("Rs.2,000 earns 20", engine.pointsEarnedOn(Money.ofMajor("2000", Currency.INR)) == 20L);
        check("$2,000 earns nothing", engine.pointsEarnedOn(Money.ofMajor("2000", Currency.USD)) == 0L);
        check("EUR earns nothing", engine.pointsEarnedOn(Money.ofMajor("9999", Currency.EUR)) == 0L);
        check("GBP earns nothing", engine.pointsEarnedOn(Money.ofMajor("9999", Currency.GBP)) == 0L);

        // Redemption: a funded wallet spends in INR and is refused in USD.
        // 500 points held, 500 offered. The 20% cap does the clamping, not the balance.
        CustomerPricingContext funded =
                new CustomerPricingContext(500L, 500L, 0L, false, null, 0, null, 0);

        Quote inr = engine.quote(coinsRate(Platform.PC, "700.00"),
                new BigDecimal("10"), funded);
        check("points spend on an INR order", inr.pointsRedeemed() > 0L);
        check("the INR wallet line is negative",
                line(inr, LineCode.WALLET_REDEMPTION).minor() < 0L);

        RateCard usdRate = new RateCard("FC26", Sku.TRADING_SERVICE, Platform.PC, null, null,
                Money.ofMajor("9.00", Currency.USD),
                new BigDecimal("0.5"), new BigDecimal("100"), new BigDecimal("0.5"));
        Quote usd = engine.quote(usdRate, new BigDecimal("10"), funded);
        check("the same wallet spends nothing on a USD order", usd.pointsRedeemed() == 0L);
        check("no wallet line on a USD order",
                line(usd, LineCode.WALLET_REDEMPTION).minor() == 0L);
        check("a USD order earns nothing", usd.pointsEarned() == 0L);
        check("the USD quote still reconciles", usd.linesReconcile());
    }

    private static void quoteSigning() {
        section("Quote signing");
        String secret = "test-secret-that-is-at-least-32-characters-long";
        QuoteSigner signer = new QuoteSigner(secret);
        PricingEngine engine = engine(taxAddedPolicy());
        Quote q = engine.quote(coinsRate(Platform.PC, "700.00"),
                new BigDecimal("3.5"), CustomerPricingContext.guest());

        String sig = signer.sign(q, "cust_42");
        Instant now = q.issuedAt();
        check("valid signature verifies", signer.verify(q, "cust_42", sig, now));
        check("wrong customer rejected", !signer.verify(q, "cust_99", sig, now));
        check("garbage signature rejected", !signer.verify(q, "cust_42", "not-a-signature", now));
        check("expired quote rejected",
                !signer.verify(q, "cust_42", sig, q.expiresAt().plusSeconds(1)));
        check("quote valid right up to expiry",
                signer.verify(q, "cust_42", sig, q.expiresAt().minusMillis(1)));

        // The whole point: tamper with the amount and the signature must fail.
        Quote tampered = new Quote(q.quoteId(), q.season(), q.sku(), q.platform(), q.variant(),
                q.quantity(), q.currency(), q.lines(), q.subtotal(),
                Money.ofMinor(1L, Currency.INR), q.pointsRedeemed(), q.pointsEarned(),
                q.referralCode(), q.couponCode(), q.issuedAt(), q.expiresAt());
        check("price tampering detected", !signer.verify(tampered, "cust_42", sig, now));

        Quote qtyTampered = new Quote(q.quoteId(), q.season(), q.sku(), q.platform(), q.variant(),
                new BigDecimal("99"), q.currency(), q.lines(), q.subtotal(), q.total(),
                q.pointsRedeemed(), q.pointsEarned(), q.referralCode(), q.couponCode(),
                q.issuedAt(), q.expiresAt());
        check("quantity tampering detected", !signer.verify(qtyTampered, "cust_42", sig, now));

        Quote pointsTampered = new Quote(q.quoteId(), q.season(), q.sku(), q.platform(), q.variant(),
                q.quantity(), q.currency(), q.lines(), q.subtotal(), q.total(),
                999_999L, q.pointsEarned(), q.referralCode(), q.couponCode(),
                q.issuedAt(), q.expiresAt());
        check("points tampering detected", !signer.verify(pointsTampered, "cust_42", sig, now));

        checkThrows("weak signing secret rejected at startup", IllegalArgumentException.class,
                () -> new QuoteSigner("short"));
    }

    private static void envelopeEncryption() {
        section("Credential vault");
        byte[] master = new byte[32];
        new SecureRandom().nextBytes(master);
        EnvelopeCipher cipher = new EnvelopeCipher(master);

        String secretPayload = "{\"eaEmail\":\"player@example.com\",\"eaPassword\":\"hunter2\","
                + "\"backupCodes\":[\"11111111\",\"22222222\"]}";
        EnvelopeCipher.Sealed sealed = cipher.seal(secretPayload);
        check("round-trips", cipher.open(sealed).equals(secretPayload));
        check("ciphertext is not the plaintext",
                !new String(sealed.ciphertext(), StandardCharsets.UTF_8).contains("hunter2"));
        check("sealed blob never prints its contents", sealed.toString().startsWith("Sealed["));

        EnvelopeCipher.Sealed again = cipher.seal(secretPayload);
        check("fresh data key each time", !java.util.Arrays.equals(sealed.wrappedDek(), again.wrappedDek()));
        check("fresh iv each time", !java.util.Arrays.equals(sealed.iv(), again.iv()));
        check("identical plaintext yields different ciphertext",
                !java.util.Arrays.equals(sealed.ciphertext(), again.ciphertext()));

        byte[] corrupted = sealed.ciphertext().clone();
        corrupted[0] ^= 0x01;
        checkThrows("tampered ciphertext fails the auth tag", IllegalStateException.class,
                () -> cipher.open(new EnvelopeCipher.Sealed(corrupted, sealed.iv(), sealed.wrappedDek())));

        byte[] otherMaster = new byte[32];
        new SecureRandom().nextBytes(otherMaster);
        EnvelopeCipher wrongKey = new EnvelopeCipher(otherMaster);
        checkThrows("wrong master key cannot unwrap", IllegalStateException.class,
                () -> wrongKey.open(sealed));

        checkThrows("undersized master key rejected", IllegalArgumentException.class,
                () -> new EnvelopeCipher(new byte[16]));
        checkThrows("missing master key rejected", IllegalArgumentException.class,
                () -> new EnvelopeCipher(null));
    }

    private static void orderStateMachine() {
        section("Order state machine");
        check("draft to awaiting payment",
                OrderStateMachine.canTransition(OrderStatus.DRAFT, OrderStatus.AWAITING_PAYMENT));
        check("awaiting payment to paid",
                OrderStateMachine.canTransition(OrderStatus.AWAITING_PAYMENT, OrderStatus.PAID));
        check("draft cannot jump to paid",
                !OrderStateMachine.canTransition(OrderStatus.DRAFT, OrderStatus.PAID));
        check("draft cannot jump to delivered",
                !OrderStateMachine.canTransition(OrderStatus.DRAFT, OrderStatus.DELIVERED));
        check("paid to credentials pending",
                OrderStateMachine.canTransition(OrderStatus.PAID, OrderStatus.CREDENTIALS_PENDING));
        check("paid straight to ready for player-auction",
                OrderStateMachine.canTransition(OrderStatus.PAID, OrderStatus.READY_FOR_DELIVERY));
        check("in progress to delivered",
                OrderStateMachine.canTransition(OrderStatus.IN_PROGRESS, OrderStatus.DELIVERED));

        // The one-way door.
        for (OrderStatus s : OrderStatus.values()) {
            if (s == OrderStatus.COMPLETED || s == OrderStatus.DISPUTED) {
                continue;
            }
            if (OrderStateMachine.canTransition(OrderStatus.DELIVERED, s)) {
                fail("DELIVERED must not transition to " + s);
                return;
            }
        }
        check("DELIVERED is a one-way door", true);

        check("terminal states have no exits",
                OrderStateMachine.nextStates(OrderStatus.COMPLETED).isEmpty()
                        && OrderStateMachine.nextStates(OrderStatus.REFUNDED).isEmpty()
                        && OrderStateMachine.nextStates(OrderStatus.ABANDONED).isEmpty()
                        && OrderStateMachine.nextStates(OrderStatus.CREDITED).isEmpty());
        check("no self-transitions",
                !OrderStateMachine.canTransition(OrderStatus.PAID, OrderStatus.PAID));
        check("null-safe", !OrderStateMachine.canTransition(null, OrderStatus.PAID));

        // Operators must never be able to conjure a payment.
        boolean operatorCanFakePayment = false;
        for (OrderStatus from : OrderStatus.values()) {
            Set<OrderStatus> ops = OrderStateMachine.operatorTransitions(from);
            if (ops.contains(OrderStatus.PAID) || ops.contains(OrderStatus.COMPLETED)
                    || ops.contains(OrderStatus.ABANDONED)) {
                operatorCanFakePayment = true;
            }
        }
        check("operators cannot mark an order paid, completed or abandoned", !operatorCanFakePayment);

        check("dispute can resolve to store credit",
                OrderStateMachine.canTransition(OrderStatus.DISPUTED, OrderStatus.CREDITED));
        check("credential-holding states enumerated",
                OrderStatus.IN_PROGRESS.mayHoldCredentials()
                        && !OrderStatus.COMPLETED.mayHoldCredentials()
                        && !OrderStatus.REFUNDED.mayHoldCredentials());

        boolean threw = false;
        try {
            OrderStateMachine.assertTransition(OrderStatus.COMPLETED, OrderStatus.IN_PROGRESS);
        } catch (RuntimeException e) {
            threw = true;
        }
        check("assertTransition throws on an illegal move", threw);
    }

    private static void razorpaySignatures() {
        section("Razorpay signatures");
        String webhookSecret = "wh_secret_value";
        String body = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":"
                + "{\"id\":\"pay_123\",\"amount\":263681}}}}";
        String good = Hmac.hexSha256(webhookSecret, body);

        check("valid webhook signature accepted",
                RazorpaySignature.verifyWebhook(body, good, webhookSecret));
        check("modified body rejected",
                !RazorpaySignature.verifyWebhook(body.replace("263681", "1"), good, webhookSecret));
        check("wrong secret rejected",
                !RazorpaySignature.verifyWebhook(body, good, "other_secret"));
        check("empty signature rejected",
                !RazorpaySignature.verifyWebhook(body, "", webhookSecret));
        check("null signature rejected",
                !RazorpaySignature.verifyWebhook(body, null, webhookSecret));
        check("blank secret rejected — never fail open",
                !RazorpaySignature.verifyWebhook(body, good, ""));

        String keySecret = "key_secret_value";
        String orderId = "order_ABC";
        String paymentId = "pay_XYZ";
        String cbSig = Hmac.hexSha256(keySecret, orderId + "|" + paymentId);
        check("valid checkout callback accepted",
                RazorpaySignature.verifyCheckoutCallback(orderId, paymentId, cbSig, keySecret));
        check("swapped ids rejected",
                !RazorpaySignature.verifyCheckoutCallback(paymentId, orderId, cbSig, keySecret));

        check("hmac matches a known vector",
                Hmac.hexSha256("key", "The quick brown fox jumps over the lazy dog")
                        .equals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8"));
        check("constant-time compare agrees with equality",
                Hmac.constantTimeEquals("abc", "abc") && !Hmac.constantTimeEquals("abc", "abd"));
        check("constant-time compare is null-safe", !Hmac.constantTimeEquals(null, "abc"));
    }

    private static void secureIdentifiers() {
        section("Identifiers");
        Set<String> refs = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            refs.add(SecureIds.orderRef("26"));
        }
        check("20,000 order refs with no collision", refs.size() == 20_000);
        String ref = SecureIds.orderRef("26");
        check("order ref shape", ref.matches("GFS-26-[0-9A-HJ-KM-NP-TV-Z]{8}"));
        check("order ref avoids ambiguous glyphs",
                ref.chars().noneMatch(c -> c == 'I' || c == 'L' || c == 'O' || c == 'U'));
        check("quote id prefixed", SecureIds.quoteId().startsWith("q_"));
        check("quote id has real entropy", SecureIds.quoteId().length() >= 26);
        check("affiliate code derived from a handle",
                SecureIds.affiliateCode("FCvinuhunter").startsWith("FCVINUHU"));
    }

    private static void pointsLedger() {
        section("Points ledger");
        check("earning is positive",
                PointsWallet.signedAmount(PointsEntryType.EARNED, 20) == 20L);
        check("redemption is negative",
                PointsWallet.signedAmount(PointsEntryType.REDEEMED, 514) == -514L);
        check("clawback is negative",
                PointsWallet.signedAmount(PointsEntryType.CLAWBACK, 20) == -20L);
        check("refund reversal restores",
                PointsWallet.signedAmount(PointsEntryType.REFUND_REVERSAL, 514) == 514L);
        check("balance is the sum of entries",
                PointsWallet.balance(List.of(20L, 40L, -514L, 514L, 20L)) == 80L);
        check("empty wallet is zero", PointsWallet.balance(List.of()) == 0L);
        checkThrows("negative magnitude rejected", IllegalArgumentException.class,
                () -> PointsWallet.signedAmount(PointsEntryType.EARNED, -5));
    }

    private static void loyaltyTiers() {
        section("Loyalty — tier ladder");

        check("nothing earned is bronze", LoyaltyTier.forLifetimePoints(0) == LoyaltyTier.BRONZE);
        check("just under silver is bronze", LoyaltyTier.forLifetimePoints(499) == LoyaltyTier.BRONZE);
        check("exactly the threshold promotes", LoyaltyTier.forLifetimePoints(500) == LoyaltyTier.SILVER);
        check("gold at 2,000", LoyaltyTier.forLifetimePoints(2_000) == LoyaltyTier.GOLD);
        check("elite at 5,000", LoyaltyTier.forLifetimePoints(5_000) == LoyaltyTier.ELITE);
        check("platinum at 8,000", LoyaltyTier.forLifetimePoints(8_000) == LoyaltyTier.PLATINUM);
        check("diamond at 10,000", LoyaltyTier.forLifetimePoints(10_000) == LoyaltyTier.DIAMOND);
        check("diamond is the ceiling",
                LoyaltyTier.forLifetimePoints(Long.MAX_VALUE) == LoyaltyTier.DIAMOND);
        // A heavily clawed-back account can compute negative. The honest answer is bronze,
        // not an exception thrown from inside a pricing call.
        check("negative lifetime is bronze", LoyaltyTier.forLifetimePoints(-40) == LoyaltyTier.BRONZE);

        check("bronze discounts nothing", LoyaltyTier.BRONZE.discountBps() == 0);
        check("diamond discounts 5%", LoyaltyTier.DIAMOND.discountBps() == 500);
        check("ladder never discounts backwards", ladderIsMonotonic());

        check("distance to next rung", LoyaltyTier.BRONZE.pointsToNext(20) == 480L);
        check("distance at a threshold", LoyaltyTier.SILVER.pointsToNext(500) == 1_500L);
        check("top of the ladder has nowhere to go", LoyaltyTier.DIAMOND.pointsToNext(99_999) == 0L);
        check("top of the ladder has no next", LoyaltyTier.DIAMOND.next() == null);

        // The anti-farming rules. Getting these backwards makes the ladder free to climb.
        check("earning builds status", PointsEntryType.EARNED.countsTowardLifetime());
        check("daily bonus builds status", PointsEntryType.DAILY_BONUS.countsTowardLifetime());
        check("spending does not demote", !PointsEntryType.REDEEMED.countsTowardLifetime());
        check("refunded points do not re-earn status",
                !PointsEntryType.REFUND_REVERSAL.countsTowardLifetime());
        check("clawback removes status", PointsEntryType.CLAWBACK.countsTowardLifetime());
    }

    private static boolean ladderIsMonotonic() {
        LoyaltyTier[] ladder = LoyaltyTier.values();
        for (int i = 1; i < ladder.length; i++) {
            if (ladder[i].thresholdPoints() <= ladder[i - 1].thresholdPoints()
                    || ladder[i].discountBps() < ladder[i - 1].discountBps()) {
                return false;
            }
        }
        return true;
    }

    private static void tierDiscountPricing() {
        section("Pricing — tier discount");
        PricingEngine engine = engine(taxAddedPolicy());
        RateCard pc = coinsRate(Platform.PC, "700.00");

        // 1M on PC: 70,000 base + 5% market tax = 73,500 subtotal.
        Quote bronze = engine.quote(pc, BigDecimal.ONE,
                new CustomerPricingContext(0, 0, 0, false, null, 0, null, 0));
        check("bronze shows no tier line",
                bronze.lines().stream().noneMatch(l -> l.code() == LineCode.TIER_DISCOUNT));
        check("bronze pays the full 75,338", bronze.total().minor() == 75_338L);

        Quote diamond = engine.quote(pc, BigDecimal.ONE,
                new CustomerPricingContext(0, 0, 10_000, false, null, 0, null, 0));
        check("diamond shows a tier line",
                diamond.lines().stream().anyMatch(l -> l.code() == LineCode.TIER_DISCOUNT));
        // 5% of 73,500 = 3,675 -> net 69,825 -> +2.5% fee = 71,570.625 -> 71,571.
        check("diamond pays 71,571", diamond.total().minor() == 71_571L);
        check("the tier line is negative", diamond.lines().stream()
                .filter(l -> l.code() == LineCode.TIER_DISCOUNT)
                .allMatch(l -> l.amount().minor() < 0));
        check("diamond costs less than bronze", diamond.total().minor() < bronze.total().minor());
        check("tier quote still reconciles", diamond.linesReconcile());

        // The switch has to actually switch it off — it is the lever for a bad month.
        PricingPolicy off = new PricingPolicy(500, MarketTaxMode.ADDED, 250, GatewayFeeMode.PASS_THROUGH,
                2_000, Currency.INR, 100L, 200_000L, 20L, Duration.ofMinutes(10), false);
        Quote disabled = engine(off).quote(pc, BigDecimal.ONE,
                new CustomerPricingContext(0, 0, 10_000, false, null, 0, null, 0));
        check("disabling tiers removes the discount", disabled.total().minor() == 75_338L);
        check("disabling tiers removes the line",
                disabled.lines().stream().noneMatch(l -> l.code() == LineCode.TIER_DISCOUNT));

        // Guests price at bronze by construction, not by a check somewhere.
        check("guests have no lifetime", CustomerPricingContext.guest().lifetimePoints() == 0L);

        // Referral and tier both come off the subtotal rather than compounding.
        Quote both = engine.quote(pc, BigDecimal.ONE,
                new CustomerPricingContext(0, 0, 10_000, true, "VINU", 1_000, null, 0));
        // 73,500 - 7,350 referral - 3,675 tier = 62,475 -> +2.5% = 64,036.875 -> 64,037.
        check("stacked discounts add, never compound", both.total().minor() == 64_037L);
        check("stacked discounts reconcile", both.linesReconcile());
    }

    private static void coachingPolicy() {
        section("Coaching — booking policy");
        CoachingPolicy policy = CoachingPolicy.launchDefaults();
        Instant start = Instant.parse("2026-09-01T18:00:00Z");

        check("well inside the window refunds the credit",
                policy.refundsCreditOnCustomerCancel(start.minus(Duration.ofDays(2)), start));
        // The boundary is inclusive: cancelling at exactly the cutoff still refunds. A
        // customer told "12 hours' notice" who gives exactly 12 hours has met the promise.
        check("exactly at the cutoff still refunds",
                policy.refundsCreditOnCustomerCancel(start.minus(Duration.ofHours(12)), start));
        check("a minute inside the cutoff does not",
                !policy.refundsCreditOnCustomerCancel(
                        start.minus(Duration.ofHours(12)).plusSeconds(60), start));
        check("after it has started does not",
                !policy.refundsCreditOnCustomerCancel(start.plusSeconds(1), start));

        check("reschedules allowed while under the cap",
                policy.canReschedule(start.minus(Duration.ofDays(1)), start, 0));
        check("reschedules blocked at the cap",
                !policy.canReschedule(start.minus(Duration.ofDays(1)), start, 2));
        check("reschedules blocked inside the cutoff",
                !policy.canReschedule(start.minus(Duration.ofHours(1)), start, 0));

        Instant now = Instant.parse("2026-09-01T10:00:00Z");
        check("lead time pushes the earliest slot out",
                policy.earliestBookableStart(now).equals(now.plus(Duration.ofHours(2))));
        check("no-show grace delays the mark",
                policy.noShowEligibleAt(start).equals(start.plus(Duration.ofMinutes(15))));
        check("credits expire thirty days out",
                policy.creditsExpireAt(now).equals(now.plus(Duration.ofDays(30))));

        checkThrows("a zero-length session is rejected", IllegalArgumentException.class,
                () -> new CoachingPolicy(Duration.ZERO, Duration.ofMinutes(40),
                        Duration.ofMinutes(30),
                        Duration.ofHours(2), Duration.ofDays(60), Duration.ofHours(12), 2,
                        Duration.ofMinutes(15), Duration.ofDays(90)));
        checkThrows("a zero-length block session is rejected", IllegalArgumentException.class,
                () -> new CoachingPolicy(Duration.ofMinutes(60), Duration.ZERO,
                        Duration.ofMinutes(30),
                        Duration.ofHours(2), Duration.ofDays(60), Duration.ofHours(12), 2,
                        Duration.ofMinutes(15), Duration.ofDays(90)));
        checkThrows("a calendar that closes before it opens is rejected",
                IllegalArgumentException.class,
                () -> new CoachingPolicy(Duration.ofMinutes(60), Duration.ofMinutes(40),
                        Duration.ofMinutes(30),
                        Duration.ofHours(2), Duration.ofMinutes(30), Duration.ofHours(12), 2,
                        Duration.ofMinutes(15), Duration.ofDays(90)));

        // The two products are different lengths, and the defaults say so.
        check("a single session is an hour",
                CoachingPolicy.launchDefaults().sessionLength()
                        .equals(Duration.ofMinutes(60)));
        check("a block session is forty minutes",
                CoachingPolicy.launchDefaults().blockSessionLength()
                        .equals(Duration.ofMinutes(40)));
        check("a block session is shorter than a single one",
                CoachingPolicy.launchDefaults().blockSessionLength()
                        .compareTo(CoachingPolicy.launchDefaults().sessionLength()) < 0);
    }

    private static void coachingStateMachine() {
        section("Coaching — session state machine");

        check("a booked session can complete",
                SessionStateMachine.canTransition(SessionStatus.SCHEDULED, SessionStatus.COMPLETED));
        check("a booked session can be cancelled by the customer",
                SessionStateMachine.canTransition(SessionStatus.SCHEDULED,
                        SessionStatus.CANCELLED_BY_CUSTOMER));
        check("a booked session can be cancelled by the coach",
                SessionStateMachine.canTransition(SessionStatus.SCHEDULED,
                        SessionStatus.CANCELLED_BY_COACH));
        check("a booked session can be marked no-show",
                SessionStateMachine.canTransition(SessionStatus.SCHEDULED, SessionStatus.NO_SHOW));

        // Every terminal state settles a credit exactly once. Any exit from one would
        // settle it a second time, in one direction or the other.
        boolean terminalsSealed = true;
        for (SessionStatus from : SessionStatus.values()) {
            if (from.isTerminal() && !SessionStateMachine.nextStates(from).isEmpty()) {
                terminalsSealed = false;
            }
        }
        check("terminal sessions have no exits", terminalsSealed);

        boolean noSelf = true;
        for (SessionStatus s : SessionStatus.values()) {
            if (SessionStateMachine.canTransition(s, s)) {
                noSelf = false;
            }
        }
        check("no self-transitions", noSelf);

        check("null-safe", !SessionStateMachine.canTransition(null, SessionStatus.COMPLETED)
                && !SessionStateMachine.canTransition(SessionStatus.SCHEDULED, null));

        // A customer must not be able to mark their own attendance in either direction.
        check("customers may only cancel",
                SessionStateMachine.customerTransitions(SessionStatus.SCHEDULED)
                        .equals(Set.of(SessionStatus.CANCELLED_BY_CUSTOMER)));
        check("customers cannot act on a finished session",
                SessionStateMachine.customerTransitions(SessionStatus.COMPLETED).isEmpty());

        check("cancellations free the slot",
                SessionStatus.CANCELLED_BY_CUSTOMER.releasesSlot()
                        && SessionStatus.CANCELLED_BY_COACH.releasesSlot());
        check("a no-show does not free the slot", !SessionStatus.NO_SHOW.releasesSlot());

        checkThrows("assertTransition throws on an illegal move",
                IllegalSessionTransitionException.class,
                () -> SessionStateMachine.assertTransition(SessionStatus.COMPLETED,
                        SessionStatus.SCHEDULED));
    }

    private static void slotPlanning() {
        section("Coaching — slot planning");
        CoachingPolicy policy = CoachingPolicy.launchDefaults();
        ZoneId kolkata = ZoneId.of("Asia/Kolkata");

        // Tuesday 18:00-22:00 IST. Single sessions are an hour on a 30-minute grid: the
        // last one that fits starts at 21:00, so 21:30 must not be offered.
        List<AvailabilityRule> evening = List.of(
                new AvailabilityRule(DayOfWeek.TUESDAY, LocalTime.of(18, 0), LocalTime.of(22, 0)));

        Instant from = ZonedDateTime.of(2026, 9, 1, 0, 0, 0, 0, kolkata).toInstant();
        Instant to = ZonedDateTime.of(2026, 9, 2, 0, 0, 0, 0, kolkata).toInstant();
        List<Instant> slots = SlotPlanner.bookableStarts(
                kolkata, evening, List.of(), new TimeRange(from, to), policy);

        // 18:00 through 21:00 on the half hour = 7 slots; 21:30 would end at 22:10.
        check("a four-hour window yields seven hour-long slots", slots.size() == 7);
        check("the window opens on time", slots.get(0)
                .equals(ZonedDateTime.of(2026, 9, 1, 18, 0, 0, 0, kolkata).toInstant()));
        check("the last slot finishes inside the window", slots.get(6)
                .equals(ZonedDateTime.of(2026, 9, 1, 21, 0, 0, 0, kolkata).toInstant()));
        check("slots come back in order", isAscending(slots));

        // A booking blocks not only its own start but every slot that overlaps it. 18:30
        // overlaps an 18:00-18:40 session by ten minutes, which a unique index on the
        // start time would happily allow.
        Instant booked = ZonedDateTime.of(2026, 9, 1, 18, 0, 0, 0, kolkata).toInstant();
        List<Instant> afterBooking = SlotPlanner.bookableStarts(kolkata, evening,
                List.of(TimeRange.of(booked, policy.sessionLength())),
                new TimeRange(from, to), policy);
        check("a booking removes two overlapping slots", afterBooking.size() == 5);
        check("the booked slot is gone", !afterBooking.contains(booked));
        check("the overlapping half-hour slot is gone", !afterBooking.contains(
                ZonedDateTime.of(2026, 9, 1, 18, 30, 0, 0, kolkata).toInstant()));
        check("the next non-overlapping slot survives", afterBooking.contains(
                ZonedDateTime.of(2026, 9, 1, 19, 0, 0, 0, kolkata).toInstant()));

        // The whole session, not merely its start, must fit inside the requested window.
        Instant tightTo = ZonedDateTime.of(2026, 9, 1, 18, 30, 0, 0, kolkata).toInstant();
        List<Instant> tight = SlotPlanner.bookableStarts(kolkata, evening, List.of(),
                new TimeRange(from, tightTo), policy);
        check("a session that would run past the window is not offered", tight.isEmpty());

        /*
         * The invariant the public slots endpoint depends on.
         *
         * That endpoint is shared-cached, so it cannot vary by caller and publishes the
         * single-session grid to everyone -- including block customers, whose sessions
         * are shorter. That is only safe if every start offered for a long session is
         * also legal for a short one. Asserted here rather than reasoned about in a
         * comment, because the day someone makes block sessions the longer product this
         * check is what fails.
         */
        List<Instant> longSlots = SlotPlanner.bookableStarts(
                kolkata, evening, List.of(), new TimeRange(from, to), policy,
                policy.sessionLength());
        List<Instant> shortSlots = SlotPlanner.bookableStarts(
                kolkata, evening, List.of(), new TimeRange(from, to), policy,
                policy.blockSessionLength());
        check("a shorter session is offered at least as many starts",
                shortSlots.size() >= longSlots.size());
        check("every start legal for a long session is legal for a short one",
                shortSlots.containsAll(longSlots));
        check("a start offered for the long session is bookable at the short length",
                longSlots.stream().allMatch(start -> SlotPlanner.isBookable(
                        start, kolkata, evening, List.of(), new TimeRange(from, to),
                        policy, policy.blockSessionLength())));

        /*
         * A shorter session never costs the coach more of the calendar.
         *
         * Stated as "no worse" rather than "strictly better", because at the current
         * 30-minute grid it is exactly equal, and that is worth knowing: a 40-minute
         * session on a 30-minute step occupies the same two grid positions a 60-minute
         * one does, so shortening the block sessions buys the coach no extra capacity at
         * all. Getting that capacity would need `slot-step` reduced to 20m or 10m, which
         * is a separate decision about how ragged the coach's day is allowed to look.
         */
        List<Instant> shortAfterBooking = SlotPlanner.bookableStarts(kolkata, evening,
                List.of(TimeRange.of(booked, policy.blockSessionLength())),
                new TimeRange(from, to), policy, policy.blockSessionLength());
        check("a 40-minute booking never blocks more starts than a 60-minute one",
                shortAfterBooking.size() >= afterBooking.size());
        check("at a 30-minute grid the two block identically",
                shortAfterBooking.size() == afterBooking.size());

        // Availability on a day the window does not reach yields nothing.
        List<AvailabilityRule> sundayOnly = List.of(
                new AvailabilityRule(DayOfWeek.SUNDAY, LocalTime.of(18, 0), LocalTime.of(22, 0)));
        check("a coach who does not work that day has no slots",
                SlotPlanner.bookableStarts(kolkata, sundayOnly, List.of(),
                        new TimeRange(from, to), policy).isEmpty());
        check("no availability means no slots", SlotPlanner.bookableStarts(
                kolkata, List.of(), List.of(), new TimeRange(from, to), policy).isEmpty());

        // ---- the twice-a-year case, tested on purpose rather than waited for ----------
        //
        // New York springs forward on 8 March 2026: 02:00 becomes 03:00. A 01:00-05:00
        // window on a 30-minute grid produces seven local start times, but 02:00 and 02:30
        // do not exist and resolve forward onto 03:00 and 03:30 — which are already in the
        // list. Offering the collision twice would hand two customers the same instant.
        ZoneId newYork = ZoneId.of("America/New_York");
        List<AvailabilityRule> earlySunday = List.of(
                new AvailabilityRule(DayOfWeek.SUNDAY, LocalTime.of(1, 0), LocalTime.of(5, 0)));
        Instant dstFrom = ZonedDateTime.of(2026, 3, 7, 12, 0, 0, 0, newYork).toInstant();
        Instant dstTo = ZonedDateTime.of(2026, 3, 9, 12, 0, 0, 0, newYork).toInstant();
        List<Instant> dstSlots = SlotPlanner.bookableStarts(
                newYork, earlySunday, List.of(), new TimeRange(dstFrom, dstTo), policy);

        check("spring-forward collisions are deduplicated",
                dstSlots.size() == new HashSet<>(dstSlots).size());
        check("the lost hour is not sold twice", dstSlots.size() == 5);
        check("dst slots come back in order", isAscending(dstSlots));
        // Offered slots are *expected* to overlap each other — a 40-minute session on a
        // 30-minute grid means 18:00 and 18:30 both get offered, and booking either one
        // removes the other via the busy set. What must survive the transition is the
        // grid itself: no two surviving instants may sit closer together than one step,
        // which is what would happen if a collapsed local time resolved somewhere odd.
        boolean gridHolds = true;
        for (int i = 1; i < dstSlots.size(); i++) {
            Duration gap = Duration.between(dstSlots.get(i - 1), dstSlots.get(i));
            if (gap.compareTo(policy.slotStep()) < 0) {
                gridHolds = false;
            }
        }
        check("the booking grid survives the transition", gridHolds);

        // The server must never trust an instant just because the client sent it.
        Instant offGrid = ZonedDateTime.of(2026, 9, 1, 18, 7, 0, 0, kolkata).toInstant();
        check("an off-grid start is refused", !SlotPlanner.isBookable(
                offGrid, kolkata, evening, List.of(), new TimeRange(from, to), policy));
        check("an out-of-hours start is refused", !SlotPlanner.isBookable(
                ZonedDateTime.of(2026, 9, 1, 3, 0, 0, 0, kolkata).toInstant(),
                kolkata, evening, List.of(), new TimeRange(from, to), policy));
        check("a genuine slot is accepted", SlotPlanner.isBookable(
                booked, kolkata, evening, List.of(), new TimeRange(from, to), policy));
        check("a taken slot is refused", !SlotPlanner.isBookable(
                booked, kolkata, evening, List.of(TimeRange.of(booked, policy.sessionLength())),
                new TimeRange(from, to), policy));
        check("null is refused", !SlotPlanner.isBookable(
                null, kolkata, evening, List.of(), new TimeRange(from, to), policy));

        // Half-open ranges: back-to-back must not read as a conflict, or the calendar
        // loses half its capacity to a phantom overlap.
        Instant t = Instant.parse("2026-09-01T12:00:00Z");
        check("touching ranges do not overlap",
                !new TimeRange(t, t.plusSeconds(2400))
                        .overlaps(new TimeRange(t.plusSeconds(2400), t.plusSeconds(4800))));
        check("genuinely overlapping ranges do",
                new TimeRange(t, t.plusSeconds(2400))
                        .overlaps(new TimeRange(t.plusSeconds(1200), t.plusSeconds(3600))));
        checkThrows("a backwards range is rejected", IllegalArgumentException.class,
                () -> new TimeRange(t.plusSeconds(60), t));
        checkThrows("a backwards availability window is rejected", IllegalArgumentException.class,
                () -> new AvailabilityRule(DayOfWeek.MONDAY, LocalTime.of(22, 0), LocalTime.of(18, 0)));
    }

    private static boolean isAscending(List<Instant> instants) {
        for (int i = 1; i < instants.size(); i++) {
            if (!instants.get(i).isAfter(instants.get(i - 1))) {
                return false;
            }
        }
        return true;
    }

    private static void coupons() {
        section("Coupons");

        // ---- the ceiling, which is the whole point -----------------------------------
        check("twenty percent is the ceiling", Coupon.MAX_DISCOUNT_BPS == 2_000);
        Coupon max = new Coupon("SAVE20", 2_000, 0L, null);
        check("a coupon at the ceiling is accepted", max.discountBps() == 2_000);
        checkThrows("one basis point over is rejected", IllegalArgumentException.class,
                () -> new Coupon("TOOBIG", 2_001, 0L, null));
        checkThrows("a wildly oversized coupon is rejected", IllegalArgumentException.class,
                () -> new Coupon("FREE", 10_000, 0L, null));
        checkThrows("a zero coupon is rejected", IllegalArgumentException.class,
                () -> new Coupon("NOTHING", 0, 0L, null));
        checkThrows("a negative coupon is rejected", IllegalArgumentException.class,
                () -> new Coupon("NEGATIVE", -500, 0L, null));

        // ---- shape --------------------------------------------------------------------
        check("codes normalise to upper case",
                new Coupon("  vinu20 ", 1_000, 0L, null).code().equals("VINU20"));
        check("normalise is null-safe", Coupon.normalise(null) == null);
        checkThrows("a blank code is rejected", IllegalArgumentException.class,
                () -> new Coupon("   ", 1_000, 0L, null));
        checkThrows("a two-character code is rejected", IllegalArgumentException.class,
                () -> new Coupon("AB", 1_000, 0L, null));
        checkThrows("spaces in a code are rejected", IllegalArgumentException.class,
                () -> new Coupon("SAVE 20", 1_000, 0L, null));
        checkThrows("punctuation in a code is rejected", IllegalArgumentException.class,
                () -> new Coupon("SAVE$20", 1_000, 0L, null));
        check("hyphens and underscores are allowed",
                new Coupon("WL-SAVE_20", 1_000, 0L, null).code().equals("WL-SAVE_20"));
        checkThrows("a negative minimum order is rejected", IllegalArgumentException.class,
                () -> new Coupon("SAVE10", 1_000, -1L, null));

        // ---- validity -----------------------------------------------------------------
        Instant now = Instant.parse("2026-09-01T12:00:00Z");
        check("a coupon with no expiry is always live",
                new Coupon("FOREVER", 500, 0L, null).isLiveAt(now));
        check("an unexpired coupon is live",
                new Coupon("SOON", 500, 0L, now.plusSeconds(60)).isLiveAt(now));
        check("an expired coupon is not",
                !new Coupon("GONE", 500, 0L, now.minusSeconds(1)).isLiveAt(now));
        check("expiry is exclusive at the instant itself",
                !new Coupon("EDGE", 500, 0L, now).isLiveAt(now));

        Coupon bigSpend = new Coupon("BIG", 1_000, 100_000L, null);
        check("an order above the minimum qualifies",
                bigSpend.acceptsOrderOf(Money.ofMinor(100_000, Currency.INR)));
        check("an order below the minimum does not",
                !bigSpend.acceptsOrderOf(Money.ofMinor(99_999, Currency.INR)));

        // ---- pricing ------------------------------------------------------------------
        PricingEngine engine = engine(taxAddedPolicy());
        RateCard pc = coinsRate(Platform.PC, "700.00");

        // 1M on PC: 70,000 base + 5% market tax = 73,500 subtotal.
        Quote plain = engine.quote(pc, BigDecimal.ONE, CustomerPricingContext.guest());
        Quote couponed = engine.quote(pc, BigDecimal.ONE,
                new CustomerPricingContext(0, 0, 0, true, null, 0, "SAVE20", 2_000));

        // 20% of 73,500 = 14,700 -> net 58,800 -> +2.5% fee = 60,270.
        check("a 20% coupon comes off the subtotal",
                line(couponed, LineCode.COUPON_DISCOUNT).minor() == -14_700L);
        check("the coupon total is right", couponed.total().minor() == 60_270L);
        check("a couponed quote reconciles", couponed.linesReconcile());
        check("the coupon is recorded on the quote", "SAVE20".equals(couponed.couponCode()));
        check("a coupon makes the order cheaper", couponed.total().minor() < plain.total().minor());
        check("no coupon line without a coupon",
                plain.lines().stream().noneMatch(l -> l.code() == LineCode.COUPON_DISCOUNT));
        check("no coupon recorded without a coupon", plain.couponCode() == null);

        // An over-ceiling discount reaching the engine is clamped rather than honoured.
        // The context rejects it first, so this is the layer below that one.
        check("the engine clamps an oversized discount", engine.quote(pc, BigDecimal.ONE,
                new CustomerPricingContext(0, 0, 0, true, null, 0, "SAVE20", 2_000))
                .total().minor() == 60_270L);
        checkThrows("the context rejects an oversized discount", IllegalArgumentException.class,
                () -> new CustomerPricingContext(0, 0, 0, true, null, 0, "HUGE", 9_000));

        // ---- the exclusion rule --------------------------------------------------------
        //
        // A coupon and a creator code both discount the same subtotal. Stacking them makes
        // an offer nobody approved, so the coupon wins and the referral stands down.
        Quote both = engine.quote(pc, BigDecimal.ONE,
                new CustomerPricingContext(0, 0, 0, true, "VINU", 1_300, "SAVE20", 2_000));
        check("a coupon suppresses the creator discount",
                both.lines().stream().noneMatch(l -> l.code() == LineCode.REFERRAL_DISCOUNT));
        check("the coupon still applies when both are present",
                line(both, LineCode.COUPON_DISCOUNT).minor() == -14_700L);
        check("the suppressed creator code is not recorded", both.referralCode() == null);
        check("coupon and referral never both bill", both.total().minor() == 60_270L);

        // The tier discount is earned, not promotional, so it is not part of the exclusion.
        Quote couponAndTier = engine.quote(pc, BigDecimal.ONE,
                new CustomerPricingContext(0, 0, 10_000, false, null, 0, "SAVE20", 2_000));
        check("a coupon stacks with an earned tier discount",
                couponAndTier.lines().stream().anyMatch(l -> l.code() == LineCode.TIER_DISCOUNT)
                        && couponAndTier.lines().stream()
                                .anyMatch(l -> l.code() == LineCode.COUPON_DISCOUNT));
        // 73,500 − 14,700 coupon − 3,675 tier = 55,125 -> +2.5% = 56,503.125 -> 56,503.
        check("coupon plus tier prices correctly", couponAndTier.total().minor() == 56_503L);
        check("coupon plus tier reconciles", couponAndTier.linesReconcile());

        // ---- the signature ------------------------------------------------------------
        //
        // The coupon is part of the signed payload, so swapping the code on a quote in
        // flight invalidates it even where the total happens to be unchanged.
        QuoteSigner signer = new QuoteSigner("a-quote-signing-secret-of-sufficient-length!!");
        String signature = signer.sign(couponed, "acct-1");
        check("a signed couponed quote verifies",
                signer.verify(couponed, "acct-1", signature, couponed.issuedAt()));

        Quote swapped = new Quote(couponed.quoteId(), couponed.season(), couponed.sku(),
                couponed.platform(), couponed.variant(), couponed.quantity(), couponed.currency(),
                couponed.lines(), couponed.subtotal(), couponed.total(), couponed.pointsRedeemed(),
                couponed.pointsEarned(), couponed.referralCode(), "OTHERCODE",
                couponed.issuedAt(), couponed.expiresAt());
        check("swapping the coupon breaks the signature",
                !signer.verify(swapped, "acct-1", signature, couponed.issuedAt()));
    }

    // ------------------------------------------------------------------ helpers

    private static RateCard coinsRate(Platform platform, String pricePerMillion) {
        return new RateCard("FC26", Sku.TRADING_SERVICE, platform, null, null,
                Money.ofMajor(pricePerMillion, Currency.INR),
                new BigDecimal("0.5"), new BigDecimal("100"), new BigDecimal("0.5"));
    }

    private static PricingEngine engine(PricingPolicy policy) {
        return new PricingEngine(
                policy,
                Clock.fixed(Instant.parse("2026-08-20T10:00:00Z"), ZoneOffset.UTC),
                () -> "q_deterministic_for_tests");
    }

    private static Money line(Quote q, LineCode code) {
        for (QuoteLine l : q.lines()) {
            if (l.code() == code) {
                return l.amount();
            }
        }
        return Money.zero(q.currency());
    }

    private static void section(String name) {
        System.out.println("\n  " + name);
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("    ok   " + what);
        } else {
            fail(what);
        }
    }

    private static void fail(String what) {
        failures.add(what);
        System.out.println("    FAIL " + what);
    }

    private static void checkThrows(String what, Class<? extends Throwable> expected, Runnable body) {
        try {
            body.run();
            fail(what + " (nothing thrown)");
        } catch (Throwable t) {
            if (expected.isInstance(t)) {
                passed++;
                System.out.println("    ok   " + what);
            } else {
                fail(what + " (threw " + t.getClass().getSimpleName() + ")");
            }
        }
    }
}
