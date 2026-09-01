package com.globalfutservice.admin;

import com.globalfutservice.catalog.RateCardEntity;
import com.globalfutservice.catalog.RateCardRepository;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.PriceUnit;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Price administration.
 *
 * <p>Changing a price is an insert, never an update. The current row is closed with a
 * {@code validTo} timestamp and a new one opened — so the history of every price is
 * preserved, an order placed last month can still be explained, and a mistaken change can
 * be undone by making another one rather than by restoring a backup.
 *
 * <p>Restricted to ADMIN rather than OPERATOR: an operator fulfils orders, but changing
 * what the business charges is a different kind of authority.
 */
@RestController
@RequestMapping("/api/v1/admin/rates")
@Tag(name = "Admin — pricing", description = "Rate card administration")
@PreAuthorize("hasRole('ADMIN')")
public class AdminRateCardController {

    private static final Logger log = LoggerFactory.getLogger(AdminRateCardController.class);

    private final RateCardRepository rates;
    private final AppProperties props;

    public AdminRateCardController(RateCardRepository rates, AppProperties props) {
        this.rates = rates;
        this.props = props;
    }

    public record RateRowDto(
            Long id, String season, String sku, String platform, String variant,
            String currency, String priceUnit, long unitPriceMinor, String unitPriceFormatted,
            BigDecimal minQuantity, BigDecimal maxQuantity, BigDecimal stepQuantity,
            String label, Instant validFrom, Instant validTo, boolean live) {
    }

    public record UpdateRateRequest(
            @NotBlank String sku,
            String platform,
            String variant,
            @NotBlank String currency,
            @Positive(message = "A price must be greater than zero")
            long unitPriceMinor,
            BigDecimal minQuantity,
            BigDecimal maxQuantity,
            BigDecimal stepQuantity,
            String label,
            Integer sortOrder) {
    }

    @GetMapping
    @Operation(summary = "Live rates for a currency")
    public ResponseEntity<List<RateRowDto>> live(@RequestParam(defaultValue = "INR") String currency) {
        Currency parsed = parse(Currency.class, currency);
        return ResponseEntity.ok(rates.findLiveForSeason(props.season(), parsed).stream()
                .map(AdminRateCardController::toDto).toList());
    }

    @GetMapping("/history")
    @Operation(summary = "Every price this SKU has ever had")
    public ResponseEntity<List<RateRowDto>> history(@RequestParam String sku,
                                                    @RequestParam(defaultValue = "INR") String currency) {
        return ResponseEntity.ok(rates.findHistory(props.season(),
                        parse(Sku.class, sku), parse(Currency.class, currency)).stream()
                .map(AdminRateCardController::toDto).toList());
    }

    @PostMapping
    @Operation(summary = "Change a price",
            description = "Closes the current row and opens a new one. Nothing is overwritten.")
    @Transactional
    public ResponseEntity<RateRowDto> update(@Valid @RequestBody UpdateRateRequest request,
                                             @CurrentAccount AccountPrincipal admin) {
        Sku sku = parse(Sku.class, request.sku());
        Currency currency = parse(Currency.class, request.currency());
        Platform platform = request.platform() == null || request.platform().isBlank()
                ? null : parse(Platform.class, request.platform());

        RateCardEntity current = rates.findLive(
                props.season(), sku, platform, request.variant(), currency).orElse(null);

        Instant now = Instant.now();
        if (current != null) {
            if (current.getUnitPriceMinor() == request.unitPriceMinor()) {
                // No-op writes would litter the history and make it useless.
                return ResponseEntity.ok(toDto(current));
            }
            current.close(now);
            rates.save(current);
            rates.flush();
        }

        RateCardEntity replacement = new RateCardEntity(
                props.season(), sku, platform, request.variant(), currency,
                sku.unit(),
                request.unitPriceMinor(),
                orElse(request.minQuantity(), current == null ? null : current.getMinQuantity()),
                orElse(request.maxQuantity(), current == null ? null : current.getMaxQuantity()),
                orElse(request.stepQuantity(), current == null ? null : current.getStepQuantity()),
                request.label() != null ? request.label() : (current == null ? null : current.getLabel()),
                request.sortOrder() != null ? request.sortOrder()
                        : (current == null ? 0 : current.getSortOrder()),
                admin.id());

        RateCardEntity saved = rates.save(replacement);
        log.info("Admin {} changed {} {} {} to {}", admin.publicId(), sku, platform,
                request.variant(), Money.ofMinor(request.unitPriceMinor(), currency).format());
        return ResponseEntity.ok(toDto(saved));
    }

    private static BigDecimal orElse(BigDecimal value, BigDecimal fallback) {
        return value != null ? value : fallback;
    }

    private static RateRowDto toDto(RateCardEntity r) {
        return new RateRowDto(
                r.getId(), r.getSeason(), r.getSku().name(),
                r.getPlatform() == null ? null : r.getPlatform().name(),
                r.getVariant(), r.getCurrency().name(),
                r.getPriceUnit() == null ? PriceUnit.FLAT.name() : r.getPriceUnit().name(),
                r.getUnitPriceMinor(),
                Money.ofMinor(r.getUnitPriceMinor(), r.getCurrency()).format(),
                r.getMinQuantity(), r.getMaxQuantity(), r.getStepQuantity(),
                r.getLabel(), r.getValidFrom(), r.getValidTo(), r.isLive());
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiExceptions.BadRequestException("Unknown value: " + raw);
        }
    }
}
