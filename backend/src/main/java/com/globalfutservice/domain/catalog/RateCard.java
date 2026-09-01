package com.globalfutservice.domain.catalog;

import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One priced row of the catalogue, valid for a window of time.
 *
 * <p>Rates are <b>never updated in place</b>. Changing a price closes the current row
 * ({@code validTo = now()}) and inserts a new one. That gives a free price-history audit
 * trail, makes "why was this order ₹700 and not ₹750?" answerable a year later, and turns
 * the admin price editor into an insert rather than a destructive write.
 *
 * @param season         e.g. {@code FC26} — bumped on each new EA title
 * @param sku            what is being priced
 * @param platform       console family; null-equivalent rows use a per-SKU default
 * @param variant        discriminator for flat SKUs, e.g. {@code WINS_11} or {@code DIV_5_TO_3}
 * @param label          what a human calls this row — "Division 1 to Elite", "12 wins ·
 *                       Elite IV". Carried into the domain because the invoice line is
 *                       built here, and a customer's receipt must not read
 *                       {@code DIV_1_TO_ELITE}. Deriving it by un-shouting the variant
 *                       would be a second implementation of a string the database already
 *                       holds, and the second one is what drifts. Nullable: a row without
 *                       a label falls back to the variant, which is ugly but honest.
 * @param unitPrice      price for one unit as defined by {@link Sku#unit()}
 * @param minQuantity    smallest orderable quantity (inclusive)
 * @param maxQuantity    largest orderable quantity (inclusive)
 * @param stepQuantity   quantity granularity; an order must land exactly on a step
 */
public record RateCard(
        String season,
        Sku sku,
        Platform platform,
        String variant,
        String label,
        Money unitPrice,
        BigDecimal minQuantity,
        BigDecimal maxQuantity,
        BigDecimal stepQuantity) {

    public RateCard {
        Objects.requireNonNull(season, "season");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (unitPrice.isNegative()) {
            throw new IllegalArgumentException("unitPrice must not be negative");
        }
        if (sku.unit() == PriceUnit.PER_MILLION) {
            Objects.requireNonNull(minQuantity, "minQuantity required for PER_MILLION");
            Objects.requireNonNull(maxQuantity, "maxQuantity required for PER_MILLION");
            Objects.requireNonNull(stepQuantity, "stepQuantity required for PER_MILLION");
            if (minQuantity.signum() <= 0) {
                throw new IllegalArgumentException("minQuantity must be positive");
            }
            if (maxQuantity.compareTo(minQuantity) < 0) {
                throw new IllegalArgumentException("maxQuantity < minQuantity");
            }
            if (stepQuantity.signum() <= 0) {
                throw new IllegalArgumentException("stepQuantity must be positive");
            }
        }
    }

    public Currency currency() {
        return unitPrice.currency();
    }

    /**
     * What to print on an invoice line for this row.
     *
     * <p>The label when there is one, the variant when there is not. Never null, because
     * the caller is building a receipt and has nowhere to put a null.
     */
    public String describe() {
        return label != null && !label.isBlank() ? label : variant;
    }

    /**
     * Validates a requested quantity against this row.
     *
     * @throws IllegalArgumentException with a message safe to show a customer
     */
    public void validateQuantity(BigDecimal quantity) {
        if (sku.unit() == PriceUnit.FLAT) {
            return;
        }
        Objects.requireNonNull(quantity, "quantity");
        if (quantity.compareTo(minQuantity) < 0) {
            throw new IllegalArgumentException(
                    "Minimum order is " + minQuantity.stripTrailingZeros().toPlainString() + "M coins");
        }
        if (quantity.compareTo(maxQuantity) > 0) {
            throw new IllegalArgumentException(
                    "Maximum order is " + maxQuantity.stripTrailingZeros().toPlainString()
                            + "M coins — split larger orders or contact support");
        }
        BigDecimal offset = quantity.subtract(minQuantity);
        BigDecimal[] divRem = offset.divideAndRemainder(stepQuantity);
        if (divRem[1].signum() != 0) {
            throw new IllegalArgumentException(
                    "Quantity must be in steps of " + stepQuantity.stripTrailingZeros().toPlainString() + "M");
        }
    }
}
