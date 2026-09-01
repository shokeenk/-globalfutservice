package com.globalfutservice.affiliate.web;

import com.globalfutservice.affiliate.AffiliateEntity;
import com.globalfutservice.affiliate.AffiliateLedgerRepository;
import com.globalfutservice.affiliate.AffiliateRepository;
import com.globalfutservice.affiliate.AffiliateService;
import com.globalfutservice.affiliate.AffiliateStatus;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * The creator programme.
 *
 * <p>This is the growth loop the business actually runs on, and the part of it creators
 * care about is the dashboard: real-time order and earnings figures. A monthly CSV loses
 * creators, because the ones worth having are being courted by three competitors who all
 * show live numbers.
 */
@RestController
@RequestMapping("/api/v1/affiliate")
@Tag(name = "Affiliate", description = "Creator codes and earnings")
public class AffiliateController {

    private final AffiliateService affiliateService;
    private final AffiliateRepository affiliates;
    private final AffiliateLedgerRepository ledger;

    public AffiliateController(AffiliateService affiliateService, AffiliateRepository affiliates,
                               AffiliateLedgerRepository ledger) {
        this.affiliateService = affiliateService;
        this.affiliates = affiliates;
        this.ledger = ledger;
    }

    public record ApplyRequest(
            @NotBlank(message = "Choose the code your audience will type")
            @Size(min = 3, max = 24, message = "Between 3 and 24 characters")
            @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "Letters, numbers, hyphen and underscore only")
            String code,

            @Size(max = 80)
            String displayName,

            @Size(max = 500, message = "Keep it brief")
            String channels) {
    }

    public record AffiliateDashboard(
            String code,
            String status,
            int commissionBps,
            int firstOrderDiscountBps,
            long referredOrders,
            long grossReferredMinor,
            String grossReferredFormatted,
            long unpaidCommissionMinor,
            String unpaidCommissionFormatted,
            long paidCommissionMinor,
            String paidCommissionFormatted,
            Instant createdAt,
            List<LedgerRow> recent) {
    }

    public record LedgerRow(long grossMinor, String grossFormatted,
                            long commissionMinor, String commissionFormatted,
                            String status, Instant at) {
    }

    /** Public: lets the checkout page confirm a code is real before the customer commits. */
    public record CodeCheckResponse(boolean valid, String code, Integer firstOrderDiscountBps,
                                    String message) {
    }

    @GetMapping("/codes/{code}")
    @Operation(summary = "Check whether a creator code is live")
    public ResponseEntity<CodeCheckResponse> check(@PathVariable String code) {
        return affiliateService.findActive(code)
                .map(a -> ResponseEntity.ok(new CodeCheckResponse(
                        true, a.getCode(), a.getFirstOrderDiscountBps(),
                        "Code applied — " + (a.getFirstOrderDiscountBps() / 100) + "% off your first order")))
                // 200 with valid:false rather than 404. A 404 makes code existence
                // enumerable, and a typo at checkout is not an error worth failing on.
                .orElseGet(() -> ResponseEntity.ok(new CodeCheckResponse(
                        false, null, null, "That code is not active.")));
    }

    @PostMapping("/apply")
    @Operation(summary = "Apply to become a creator partner")
    public ResponseEntity<AffiliateDashboard> apply(@Valid @RequestBody ApplyRequest request,
                                                    @CurrentAccount AccountPrincipal principal) {
        requireSignedIn(principal);
        AffiliateEntity created = affiliateService.apply(
                principal.id(), request.code(), request.displayName(), request.channels());
        return ResponseEntity.status(HttpStatus.CREATED).body(dashboard(created));
    }

    @GetMapping("/me")
    @Operation(summary = "The signed-in creator's live dashboard")
    public ResponseEntity<List<AffiliateDashboard>> mine(@CurrentAccount AccountPrincipal principal) {
        requireSignedIn(principal);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(affiliates.findByAccountId(principal.id()).stream()
                        .map(this::dashboard).toList());
    }

    private AffiliateDashboard dashboard(AffiliateEntity affiliate) {
        long gross = ledger.grossReferred(affiliate.getId());
        long unpaid = ledger.unpaidCommission(affiliate.getId());
        long paid = ledger.paidCommission(affiliate.getId());

        List<LedgerRow> recent = ledger
                .findByAffiliateIdOrderByCreatedAtDesc(affiliate.getId(), PageRequest.of(0, 20))
                .getContent().stream()
                .map(l -> new LedgerRow(
                        l.getGrossMinor(), Money.ofMinor(l.getGrossMinor(), l.getCurrency()).format(),
                        l.getCommissionMinor(),
                        Money.ofMinor(l.getCommissionMinor(), l.getCurrency()).format(),
                        l.getStatus(), l.getCreatedAt()))
                .toList();

        return new AffiliateDashboard(
                affiliate.getCode(),
                affiliate.getStatus().name(),
                affiliate.getCommissionBps(),
                affiliate.getFirstOrderDiscountBps(),
                ledger.orderCount(affiliate.getId()),
                gross, Money.ofMinor(gross, Currency.INR).format(),
                unpaid, Money.ofMinor(unpaid, Currency.INR).format(),
                paid, Money.ofMinor(paid, Currency.INR).format(),
                affiliate.getCreatedAt(),
                recent);
    }

    private static void requireSignedIn(AccountPrincipal principal) {
        if (principal == null) {
            throw new ApiExceptions.ForbiddenException("Please sign in.");
        }
    }

    /** Kept here so the enum import is used and the status vocabulary stays visible. */
    static boolean isLive(AffiliateEntity affiliate) {
        return affiliate.getStatus() == AffiliateStatus.ACTIVE;
    }
}
