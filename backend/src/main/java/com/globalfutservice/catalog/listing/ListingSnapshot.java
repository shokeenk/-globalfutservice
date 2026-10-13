package com.globalfutservice.catalog.listing;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.pricing.Quote;

import java.time.Instant;
import java.util.List;

/**
 * What an Objectives or Tournaments order was sold as, frozen when it was placed
 * ({@code orders.listing_snapshot}): the listing's words and terms as the customer read
 * them, the hours, the price and the currency. The order page, the tracking page and the
 * admin show this, never the listing, so editing or deleting a listing changes no order.
 *
 * @param category              BOOST_OBJECTIVES or BOOST_TOURNAMENTS
 * @param pricingType           FIXED or PER_HOUR
 * @param hours                 the hours bought; null at a fixed price
 * @param unitPriceMinor        the listing's price, per hour when sold by the hour
 * @param priceMinor            what the listing came to before discounts and fees: the
 *                              price, or the hourly price times the hours
 * @param imageId               the artwork it was bought with; its bytes never change
 * @param hourlyAcknowledgedAt  when the customer ticked the per-hour acknowledgement
 * @param hourlyAcknowledgement the sentence they ticked
 */
public record ListingSnapshot(
        long listingId,
        String category,
        String categoryName,
        String title,
        String subtitle,
        String overview,
        List<String> requirements,
        Integer requiredGames,
        String estimatedTime,
        String schedulingNote,
        String reward,
        String customerInstructions,
        String pricingType,
        Integer hours,
        long unitPriceMinor,
        String unitPriceFormatted,
        long priceMinor,
        String priceFormatted,
        String currency,
        Long imageId,
        Instant hourlyAcknowledgedAt,
        String hourlyAcknowledgement) {

    /**
     * Its own JSON settings, so what is stored never depends on how the application's mapper
     * happens to be configured: dates as ISO text, and fields added later read as absent.
     */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("A listing snapshot could not be written.", e);
        }
    }

    /** Null for no snapshot, or one that cannot be read -- an order page must still open. */
    public static ListingSnapshot fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, ListingSnapshot.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** The sentence a per-hour customer ticks, recorded with the order in these words. */
    public static final String HOURLY_ACKNOWLEDGEMENT =
            "I understand I'm paying for the manual service time, not for a guaranteed level or specific result.";

    /**
     * The price is the quote's, not the listing's: a quote holds its price until it expires,
     * so what the order was charged is what the quote says even if the listing has been
     * repriced since. A listing's subtotal is its price times the hours -- no market tax
     * applies to it -- so the hourly price is that divided back by the hours, exactly.
     */
    public static ListingSnapshot of(ServiceListingEntity l, Quote quote, Instant acknowledgedAt) {
        boolean hourly = l.getPricingType() == ServiceListingEntity.PricingType.PER_HOUR;
        Money price = quote.subtotal();
        long hours = hourly ? quote.quantity().longValueExact() : 1L;
        Money unit = Money.ofMinor(price.minor() / hours, price.currency());
        return new ListingSnapshot(l.getId(), l.getSku().name(), ServiceListingService.categoryName(l.getSku()),
                l.getTitle(), l.getSubtitle(), l.getOverview(), ServiceListingService.lines(l.getRequirements()),
                l.getRequiredGames(), l.getEstimatedTime(), l.getSchedulingNote(), l.getReward(),
                l.getCustomerInstructions(), l.getPricingType().name(),
                hourly ? quote.quantity().intValueExact() : null,
                unit.minor(), unit.format(), price.minor(), price.format(), quote.currency().name(),
                l.getImageId(), hourly ? acknowledgedAt : null, hourly ? HOURLY_ACKNOWLEDGEMENT : null);
    }

    /** As the order page and the admin read it: with the artwork's address. */
    public record View(
            long listingId, String category, String categoryName, String title, String subtitle, String overview,
            List<String> requirements, Integer requiredGames, String estimatedTime, String schedulingNote,
            String reward, String customerInstructions, String pricingType, Integer hours, long unitPriceMinor,
            String unitPriceFormatted, long priceMinor, String priceFormatted, String currency, String imageUrl,
            Instant hourlyAcknowledgedAt, String hourlyAcknowledgement) {
    }

    public View view() {
        return new View(listingId, category, categoryName, title, subtitle, overview, requirements, requiredGames,
                estimatedTime, schedulingNote, reward, customerInstructions, pricingType, hours, unitPriceMinor,
                unitPriceFormatted, priceMinor, priceFormatted, currency, ServiceListingService.imageUrl(imageId),
                hourlyAcknowledgedAt, hourlyAcknowledgement);
    }
}
