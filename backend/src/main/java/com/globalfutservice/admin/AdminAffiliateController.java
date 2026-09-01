package com.globalfutservice.admin;

import com.globalfutservice.affiliate.AffiliateEntity;
import com.globalfutservice.affiliate.AffiliateRepository;
import com.globalfutservice.affiliate.AffiliateService;
import com.globalfutservice.affiliate.AffiliateStatus;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Approving creators and setting their terms.
 *
 * <p>ADMIN only. Commission rates are money, and an operator's job is fulfilment.
 *
 * <p>Codes do not work until they are approved here, which is deliberate: a self-service
 * code that discounts on creation would let anyone mint themselves 13% off by filling in
 * a form.
 */
@RestController
@RequestMapping("/api/v1/admin/affiliates")
@Tag(name = "Admin — affiliates", description = "Creator programme administration")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAffiliateController {

    private static final Logger log = LoggerFactory.getLogger(AdminAffiliateController.class);

    private final AffiliateRepository affiliates;
    private final AffiliateService affiliateService;

    public AdminAffiliateController(AffiliateRepository affiliates, AffiliateService affiliateService) {
        this.affiliates = affiliates;
        this.affiliateService = affiliateService;
    }

    public record AffiliateRow(
            Long id, String code, String displayName, String channels, String status,
            int commissionBps, int firstOrderDiscountBps, Instant createdAt, Instant approvedAt) {
    }

    public record ApproveRequest(
            @Min(value = 0, message = "Commission cannot be negative")
            @Max(value = 5000, message = "Commission above 50% is almost certainly a typo")
            Integer commissionBps,

            @Min(value = 0, message = "Discount cannot be negative")
            @Max(value = 5000, message = "A discount above 50% is almost certainly a typo")
            Integer firstOrderDiscountBps) {
    }

    @GetMapping
    @Operation(summary = "List creators, optionally by status")
    public ResponseEntity<List<AffiliateRow>> list(@RequestParam(required = false) String status) {
        List<AffiliateEntity> rows;
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            rows = affiliates.findAll();
        } else {
            try {
                rows = affiliates.findByStatusOrderByCreatedAtDesc(
                        AffiliateStatus.valueOf(status.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new ApiExceptions.BadRequestException("Unknown status.");
            }
        }
        return ResponseEntity.ok(rows.stream().map(AdminAffiliateController::toRow).toList());
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a creator and set their terms")
    public ResponseEntity<AffiliateRow> approve(@PathVariable Long id,
                                                @Valid @RequestBody ApproveRequest request,
                                                @CurrentAccount AccountPrincipal admin) {
        AffiliateEntity approved = affiliateService.approve(
                id, request.commissionBps(), request.firstOrderDiscountBps());
        log.info("Admin {} approved affiliate {} at {} bps",
                admin.publicId(), approved.getCodeNormalised(), approved.getCommissionBps());
        return ResponseEntity.ok(toRow(approved));
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspend a creator code")
    public ResponseEntity<AffiliateRow> suspend(@PathVariable Long id,
                                                @CurrentAccount AccountPrincipal admin) {
        AffiliateEntity affiliate = affiliates.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such affiliate."));
        affiliate.setStatus(AffiliateStatus.SUSPENDED);
        log.info("Admin {} suspended affiliate {}", admin.publicId(), affiliate.getCodeNormalised());
        return ResponseEntity.ok(toRow(affiliates.save(affiliate)));
    }

    private static AffiliateRow toRow(AffiliateEntity a) {
        return new AffiliateRow(a.getId(), a.getCode(), a.getDisplayName(), a.getChannels(),
                a.getStatus().name(), a.getCommissionBps(), a.getFirstOrderDiscountBps(),
                a.getCreatedAt(), a.getApprovedAt());
    }
}
