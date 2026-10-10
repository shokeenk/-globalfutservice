package com.globalfutservice.admin;

import com.globalfutservice.domain.pricing.Coupon;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.pricing.CouponDeletionEntity;
import com.globalfutservice.pricing.CouponDeletionRepository;
import com.globalfutservice.pricing.CouponEntity;
import com.globalfutservice.pricing.CouponRepository;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Coupon administration.
 *
 * <p>ADMIN, not OPERATOR — on the same reasoning as the rate cards. An operator fulfils
 * orders; deciding what the business charges, and who gets to pay less, is a different
 * authority.
 *
 * <p>Deleting one ({@link CouponDeletion}) removes a coupon no order has used, and keeps --
 * marked deleted -- one that orders have: an expired campaign still has to explain the
 * orders it discounted, and removing its row would orphan every redemption pointing at it.
 * A deleted coupon is gone from the list and from checkout, and its code can be issued
 * again. Switching one off, as before, keeps it listed and its code taken.
 */
@RestController
@RequestMapping("/api/v1/admin/coupons")
@Tag(name = "Admin · Coupons", description = "Discount codes")
public class AdminCouponController {

    private static final Logger log = LoggerFactory.getLogger(AdminCouponController.class);

    private final CouponRepository coupons;
    private final CouponDeletion deletion;
    private final CouponDeletionRepository deletions;
    private final AccountRepository accounts;

    public AdminCouponController(CouponRepository coupons, CouponDeletion deletion,
                                 CouponDeletionRepository deletions, AccountRepository accounts) {
        this.coupons = coupons;
        this.deletion = deletion;
        this.deletions = deletions;
        this.accounts = accounts;
    }

    // ------------------------------------------------------------------ records ------

    public record CreateRequest(
            @NotBlank
            @Size(min = 3, max = 32, message = "A code is 3 to 32 characters")
            @Pattern(regexp = "^[A-Za-z0-9_-]+$",
                     message = "Letters, numbers, hyphens and underscores only")
            String code,

            /**
             * Whole percent, 1 to 20.
             *
             * <p>Taken as a percentage rather than basis points because that is what the
             * person filling the form is thinking in, and asking an admin to type 1500 for
             * 15% is how somebody eventually types 15 and gives away nothing — or types
             * 150 and gives away far too much. Converted to bps on the way in.
             */
            @Min(value = 1, message = "A coupon must discount at least 1%")
            @Max(value = 20, message = "A coupon cannot exceed 20%")
            int discountPercent,

            @Size(max = 140) String description,

            /** Null for unlimited. */
            @Min(1) Integer maxRedemptions,

            @Min(1) Integer maxPerAccount,

            /** Minimum order subtotal in minor units. 0 for none. */
            @PositiveOrZero Long minOrderMinor,

            Instant expiresAt) {
    }

    public record UpdateRequest(
            @Size(max = 140) String description,
            @Min(1) Integer maxRedemptions,
            @Min(1) Integer maxPerAccount,
            @PositiveOrZero Long minOrderMinor,
            Instant expiresAt,
            Boolean active) {
    }

    public record CouponView(
            Long id,
            String code,
            int discountPercent,
            int discountBps,
            String description,
            Integer maxRedemptions,
            int redeemedCount,
            Integer remaining,
            int maxPerAccount,
            long minOrderMinor,
            Instant expiresAt,
            boolean active,
            boolean exhausted,
            Instant createdAt) {
    }

    /**
     * {@code outcome}: REMOVED when no order had used it and it was deleted outright; HIDDEN
     * when orders had, and it was kept for them.
     */
    public record DeleteResponse(Long id, String code, String outcome) {
    }

    /** A deleted coupon, read-only: what it was, what deleting did, and who did it when. */
    public record DeletedView(Long couponId, String code, int discountPercent, int redeemedCount, String outcome,
                              String deletedBy, Instant deletedAt) {
    }

    // ------------------------------------------------------------------ endpoints ----

    @GetMapping
    @PreAuthorize("hasRole('OPERATOR')")
    @Operation(summary = "Every coupon that is not deleted, newest first")
    @Transactional(readOnly = true)
    public List<CouponView> list(@RequestParam(defaultValue = "100") int size) {
        return coupons.findAllByDeletedAtIsNullOrderByCreatedAtDesc(
                        PageRequest.of(0, Math.min(Math.max(size, 1), 200)))
                .getContent().stream().map(AdminCouponController::toView).toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a coupon")
    @Transactional
    public CouponView create(@CurrentAccount AccountPrincipal principal,
                             @Valid @RequestBody CreateRequest request) {

        String code = Coupon.normalise(request.code());
        // Checked here for a civil message; the unique index is what actually enforces it
        // when two admins create the same code in the same second.
        if (coupons.existsByCodeAndDeletedAtIsNull(code)) {
            throw new ApiExceptions.ConflictException("coupon_exists",
                    "A coupon with that code already exists.");
        }

        // Percent to basis points. The domain record re-checks the ceiling, so a request
        // that somehow evaded @Max still cannot create a 90% coupon.
        int bps = request.discountPercent() * 100;

        CouponEntity coupon;
        try {
            coupon = new CouponEntity(code, bps, principal.id());
        } catch (IllegalArgumentException rejected) {
            throw new ApiExceptions.BadRequestException("invalid_coupon", rejected.getMessage());
        }

        coupon.setDescription(request.description());
        coupon.setMaxRedemptions(request.maxRedemptions());
        if (request.maxPerAccount() != null) {
            coupon.setMaxPerAccount(request.maxPerAccount());
        }
        if (request.minOrderMinor() != null) {
            coupon.setMinOrderMinor(request.minOrderMinor());
        }
        coupon.setExpiresAt(request.expiresAt());

        coupons.saveAndFlush(coupon);
        log.info("Coupon {} created at {}% by account {}", code, request.discountPercent(),
                principal.id());
        return toView(coupon);
    }

    /**
     * Update a coupon's limits.
     *
     * <p>The discount itself is deliberately not editable. Changing what a live code is
     * worth silently rewrites an offer people are already sharing, and the redemption rows
     * would then disagree with the coupon they point at. To change a rate, deactivate the
     * code and issue a new one — which also leaves an honest record of both.
     */
    @PostMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update a coupon's limits, or switch it off")
    @Transactional
    public CouponView update(@PathVariable Long id, @Valid @RequestBody UpdateRequest request) {
        // A deleted coupon is read-only: there is nothing left to switch on or change.
        CouponEntity coupon = coupons.findForUpdate(id).filter(c -> !c.isDeleted())
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such coupon."));

        if (request.description() != null) {
            coupon.setDescription(request.description());
        }
        if (request.maxRedemptions() != null) {
            // Cannot be lowered below what has already been claimed: the CHECK constraint
            // would reject it, and a count above its own cap is an unrepresentable state.
            if (request.maxRedemptions() < coupon.getRedeemedCount()) {
                throw new ApiExceptions.BadRequestException("limit_below_redeemed",
                        "That code has already been used " + coupon.getRedeemedCount()
                                + " times. The limit cannot be set below that.");
            }
            coupon.setMaxRedemptions(request.maxRedemptions());
        }
        if (request.maxPerAccount() != null) {
            coupon.setMaxPerAccount(request.maxPerAccount());
        }
        if (request.minOrderMinor() != null) {
            coupon.setMinOrderMinor(request.minOrderMinor());
        }
        if (request.expiresAt() != null) {
            coupon.setExpiresAt(request.expiresAt());
        }
        if (request.active() != null) {
            coupon.setActive(request.active());
        }

        coupons.save(coupon);
        return toView(coupon);
    }

    /**
     * Delete a coupon: outright when no order has used it, otherwise kept for those orders
     * and marked deleted. Either way it leaves the list and checkout, and its code is free.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a coupon; one orders have used is kept for them, hidden and never applied again")
    public DeleteResponse delete(@CurrentAccount AccountPrincipal principal, @PathVariable Long id) {
        CouponDeletion.Deleted deleted = deletion.delete(id, principal.id());
        return new DeleteResponse(deleted.id(), deleted.code(), deleted.outcome().name());
    }

    @GetMapping("/deleted")
    @PreAuthorize("hasRole('OPERATOR')")
    @Operation(summary = "Coupons that were deleted, newest first: read-only")
    @Transactional(readOnly = true)
    public List<DeletedView> deleted(@RequestParam(defaultValue = "100") int size) {
        List<CouponDeletionEntity> rows = deletions.findAllByOrderByDeletedAtDesc(
                PageRequest.of(0, Math.min(Math.max(size, 1), 200))).getContent();
        Map<Long, String> who = accounts.findAllById(rows.stream().map(CouponDeletionEntity::getDeletedBy)
                        .filter(java.util.Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(AccountEntity::getId, AccountEntity::getEmail, (a, b) -> a));
        return rows.stream().map(d -> new DeletedView(d.getCouponId(), d.getCode(), d.getDiscountBps() / 100,
                d.getRedeemedCount(), d.getOutcome().name(), d.getDeletedBy() == null ? null : who.get(d.getDeletedBy()),
                d.getDeletedAt())).toList();
    }

    private static CouponView toView(CouponEntity c) {
        return new CouponView(
                c.getId(),
                c.getCode(),
                c.getDiscountBps() / 100,
                c.getDiscountBps(),
                c.getDescription(),
                c.getMaxRedemptions(),
                c.getRedeemedCount(),
                c.remaining(),
                c.getMaxPerAccount(),
                c.getMinOrderMinor(),
                c.getExpiresAt(),
                c.isActive(),
                c.isExhausted(),
                c.getCreatedAt());
    }
}
