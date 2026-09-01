package com.globalfutservice.catalog.web;

import com.globalfutservice.catalog.CatalogService;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
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

    static Currency parseCurrency(String raw) {
        try {
            return Currency.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiExceptions.BadRequestException(
                    "unsupported_currency", "Unknown currency.");
        }
    }
}
