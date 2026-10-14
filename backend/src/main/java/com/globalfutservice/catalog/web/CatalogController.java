package com.globalfutservice.catalog.web;

import com.globalfutservice.catalog.CatalogService;
import com.globalfutservice.catalog.listing.ServiceListingImageEntity;
import com.globalfutservice.catalog.listing.ServiceListingService;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/catalog")
@Tag(name = "Catalogue", description = "What is for sale, and the policy behind the prices")
public class CatalogController {

    private final CatalogService catalogService;
    private final ServiceListingService serviceListings;

    public CatalogController(CatalogService catalogService, ServiceListingService serviceListings) {
        this.catalogService = catalogService;
        this.serviceListings = serviceListings;
    }

    @GetMapping
    @Operation(summary = "Live services and prices for a currency")
    public ResponseEntity<CatalogDtos.CatalogResponse> catalogue(
            @RequestParam(defaultValue = "INR") String currency) {
        var response = catalogService.catalogue(parseCurrency(currency));
        // Short public cache: prices change on the order of days, and the configurator
        // hits this on every page load. Long enough to matter, short enough that a price
        // correction is live within a minute.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePublic())
                .body(response);
    }

    @GetMapping("/policy")
    @Operation(summary = "Loyalty, guarantee and fee parameters used to render site copy")
    public ResponseEntity<CatalogDtos.PolicyResponse> policy() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(300, TimeUnit.SECONDS).cachePublic())
                .body(catalogService.policy());
    }

    /**
     * An Objectives or Tournaments card's artwork. An image id's bytes never change -- a new
     * upload is a new id -- so it is cached for a year, and replacing the artwork changes the
     * URL the catalogue hands out rather than waiting for a cache to expire.
     */
    @GetMapping("/listing-images/{id}")
    @Operation(summary = "A listing's card artwork (PNG)")
    public ResponseEntity<byte[]> listingImage(@PathVariable long id) {
        ServiceListingImageEntity image = serviceListings.imageBytes(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such image."));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"listing-" + id + ".png\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(image.getBytes());
    }

    static Currency parseCurrency(String raw) {
        try {
            return Currency.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiExceptions.BadRequestException(
                    "unsupported_currency", "Unknown currency.");
        }
    }
}
