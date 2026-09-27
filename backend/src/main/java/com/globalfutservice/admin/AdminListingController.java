package com.globalfutservice.admin;

import com.globalfutservice.catalog.ListingService;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;

/**
 * Service listings: what boosting and coaching cost, whether they are on sale, and how
 * boosting is presented.
 *
 * <p>ADMIN only, like the coin rate card: this sets what customers are charged. Every
 * price change closes a row and opens another, as the rate card always has, so orders
 * already placed keep their price.
 */
@RestController
@RequestMapping("/api/v1/admin/listings")
@Tag(name = "Admin — listings", description = "Boosting and coaching prices and presentation")
@PreAuthorize("hasRole('ADMIN')")
public class AdminListingController {

    private final ListingService listings;

    public AdminListingController(ListingService listings) {
        this.listings = listings;
    }

    @GetMapping
    @Operation(summary = "Every listing, by service, with prices in each currency")
    public ResponseEntity<ListingService.Overview> overview() {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(listings.overview());
    }

    public record SaveRequest(
            /** Minor units per currency code; a currency left out keeps its price. */
            @NotNull Map<String, Long> prices,
            boolean active,
            /** Basis points, or null for no published rate. Boosting only. */
            Integer successRateBps,
            boolean bestValue) {
    }

    @PutMapping("/{sku}/{variant}")
    @Operation(summary = "Save one listing: prices, on or off, success rate, Best Value")
    public ResponseEntity<ListingService.Overview> save(@PathVariable String sku, @PathVariable String variant,
                                                        @Valid @RequestBody SaveRequest request,
                                                        @CurrentAccount AccountPrincipal admin) {
        listings.save(sku(sku), variant, new ListingService.Change(request.prices(), request.active(),
                request.successRateBps(), request.bestValue()), admin.id());
        return ResponseEntity.ok(listings.overview());
    }

    public record AddRequest(
            @NotBlank String sku,
            @NotBlank(message = "Give the listing a name") String name,
            @NotNull Map<String, Long> prices,
            Integer successRateBps) {
    }

    public record Added(String sku, String variant) {
    }

    @PostMapping
    @Operation(summary = "Add a Champs or Rivals tier")
    public ResponseEntity<Added> add(@Valid @RequestBody AddRequest request, @CurrentAccount AccountPrincipal admin) {
        Sku sku = sku(request.sku());
        String variant = listings.add(new ListingService.NewListing(sku, request.name(), request.prices(),
                request.successRateBps()), admin.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(new Added(sku.name(), variant));
    }

    private static Sku sku(String value) {
        try {
            return Sku.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiExceptions.BadRequestException("Unknown service.");
        }
    }
}
