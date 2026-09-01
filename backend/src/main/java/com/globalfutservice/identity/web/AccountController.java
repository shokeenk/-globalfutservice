package com.globalfutservice.identity.web;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.loyalty.LoyaltyTier;
import com.globalfutservice.loyalty.LoyaltyService;
import com.globalfutservice.loyalty.LoyaltyStatus;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * The customer's own account surface.
 *
 * <p>Every method here scopes its query by {@code principal.id()}. There is no path that
 * takes an account identifier from the caller — the single most reliable way to avoid
 * broken object-level authorisation is to never accept the object's id in the first
 * place.
 */
@RestController
@RequestMapping("/api/v1/account")
@Tag(name = "Account", description = "Points wallet and statements")
public class AccountController {

    private final LoyaltyService loyaltyService;
    private final AppProperties props;

    public AccountController(LoyaltyService loyaltyService, AppProperties props) {
        this.loyaltyService = loyaltyService;
        this.props = props;
    }

    public record WalletEntry(String type, long amount, String description, Instant at) {
    }

    /**
     * Balance plus the parameters behind it.
     *
     * <p>The earn rate and redemption cap are returned alongside the balance so the
     * account page can explain the scheme from live configuration rather than from
     * hard-coded copy that quietly stops matching the engine.
     */
    public record WalletResponse(
            long balance,
            long lifetimeEarned,
            long valueMinor,
            String valueFormatted,
            long pointValueMinor,
            long earnSpendUnitMinor,
            long earnPointsPerUnit,
            int maxRedemptionBps,
            List<WalletEntry> statement) {
    }

    @GetMapping("/wallet")
    @Operation(summary = "Points balance and recent statement")
    public ResponseEntity<WalletResponse> wallet(
            @CurrentAccount AccountPrincipal principal,
            @RequestParam(defaultValue = "25") int size) {

        if (principal == null) {
            throw new ApiExceptions.ForbiddenException("Please sign in.");
        }

        long balance = loyaltyService.balance(principal.id());
        long valueMinor = balance * props.pricing().pointValueMinor();

        List<WalletEntry> statement = loyaltyService
                .statement(principal.id(), PageRequest.of(0, Math.min(Math.max(size, 1), 100)))
                .getContent().stream()
                .map(e -> new WalletEntry(e.getEntryType().name(), e.getAmount(),
                        e.getDescription(), e.getCreatedAt()))
                .toList();

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new WalletResponse(
                        balance,
                        loyaltyService.lifetimeEarned(principal.id()),
                        valueMinor,
                        Money.ofMinor(valueMinor, Currency.INR).format(),
                        props.pricing().pointValueMinor(),
                        props.pricing().earnSpendUnitMinor(),
                        props.pricing().earnPointsPerUnit(),
                        props.pricing().maxWalletRedemptionBps(),
                        statement));
    }

    /**
     * Standing on the loyalty ladder.
     *
     * <p>Assembled server-side, including the tier and the distance to the next one. The
     * storefront never recomputes a tier from a points balance — that is precisely how a
     * rewards page ends up promising a discount the checkout does not apply.
     */
    public record TierView(
            String tier,
            long lifetimePoints,
            long balancePoints,
            long pointsToNextTier,
            String nextTier,
            int discountBps,
            boolean canClaimDaily,
            long dailyBonusPoints) {
    }

    @GetMapping("/loyalty")
    @Operation(summary = "Loyalty tier, lifetime points and daily-bonus availability")
    public ResponseEntity<TierView> loyalty(@CurrentAccount AccountPrincipal principal) {
        if (principal == null) {
            throw new ApiExceptions.ForbiddenException("Please sign in.");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(toTierView(loyaltyService.status(principal.id())));
    }

    /**
     * Claim today's check-in bonus.
     *
     * <p>Returns the resulting standing either way rather than erroring on a repeat claim.
     * A double tap on a phone is not a client mistake worth a 409 — the honest answer is
     * "here is your balance, you already claimed today", which is exactly what the
     * response says.
     */
    @PostMapping("/loyalty/daily")
    @Operation(summary = "Claim the daily check-in bonus")
    public ResponseEntity<TierView> claimDaily(@CurrentAccount AccountPrincipal principal) {
        if (principal == null) {
            throw new ApiExceptions.ForbiddenException("Please sign in.");
        }
        loyaltyService.claimDailyBonus(principal.id());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(toTierView(loyaltyService.status(principal.id())));
    }

    private static TierView toTierView(LoyaltyStatus s) {
        LoyaltyTier next = s.tier().next();
        return new TierView(
                s.tier().name(),
                s.lifetimePoints(),
                s.balancePoints(),
                s.pointsToNextTier(),
                next == null ? null : next.name(),
                s.discountBps(),
                s.canClaimDaily(),
                s.dailyBonusPoints());
    }
}
