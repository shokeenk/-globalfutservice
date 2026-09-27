package com.globalfutservice.admin;

import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.web.ApiExceptions;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * What the Orders page is asking for, parsed once and checked.
 *
 * <p>Every field is optional; an empty set or a null means "any". The page's tabs are
 * groups of real statuses and services rather than new states — "Boosting" is Champs and
 * Rivals, "Completed" is Delivered and Completed — so the group is expanded here, and the
 * query below only ever compares against real enum values.
 *
 * <p>Dates are whole days in the business's time zone, the way the date picker shows
 * them. {@code to} is inclusive for the reader and turned into an exclusive instant here,
 * so an order placed at 23:59 IST on the last day is in the range.
 */
public record AdminOrderFilter(
        Set<Sku> skus,
        Set<OrderStatus> statuses,
        Platform platform,
        Instant createdFrom,
        Instant createdBefore,
        String search,
        boolean attention) {

    /** Long enough for an email address or a transaction hash, and no longer. */
    static final int MAX_SEARCH = 100;

    public static AdminOrderFilter none() {
        return new AdminOrderFilter(Set.of(), Set.of(), null, null, null, null, false);
    }

    public static AdminOrderFilter parse(String service, String status, String platform,
                                         LocalDate from, LocalDate to, String search,
                                         boolean attention, ZoneId zone) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new ApiExceptions.BadRequestException("The date range ends before it starts.");
        }
        String trimmed = search == null ? null : search.trim();
        if (trimmed != null && trimmed.length() > MAX_SEARCH) {
            throw new ApiExceptions.BadRequestException("That search is too long.");
        }
        return new AdminOrderFilter(
                services(service),
                statuses(status),
                platform(platform),
                from == null ? null : from.atStartOfDay(zone).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant(),
                trimmed == null || trimmed.isEmpty() ? null : trimmed,
                attention);
    }

    /**
     * A service tab or dropdown value, as the SKUs it covers.
     *
     * <p>BOOSTING is the one group: the page has a single Boosting tab over two SKUs. The
     * dropdown can still pick either on its own.
     */
    static Set<Sku> services(String value) {
        if (blank(value)) {
            return Set.of();
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "COINS" -> EnumSet.of(Sku.TRADING_SERVICE);
            case "BOOSTING" -> EnumSet.of(Sku.BOOST_CHAMPS, Sku.BOOST_RIVALS);
            case "CHAMPS" -> EnumSet.of(Sku.BOOST_CHAMPS);
            case "RIVALS" -> EnumSet.of(Sku.BOOST_RIVALS);
            case "COACHING" -> EnumSet.of(Sku.COACHING);
            default -> throw new ApiExceptions.BadRequestException("Unknown service filter.");
        };
    }

    /** A comma-separated list of real statuses, e.g. {@code DELIVERED,COMPLETED}. */
    static Set<OrderStatus> statuses(String value) {
        if (blank(value) || "ALL".equalsIgnoreCase(value.trim())) {
            return Set.of();
        }
        EnumSet<OrderStatus> out = EnumSet.noneOf(OrderStatus.class);
        for (String part : value.split(",")) {
            if (part.isBlank()) {
                continue;
            }
            try {
                out.add(OrderStatus.valueOf(part.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new ApiExceptions.BadRequestException("Unknown status filter.");
            }
        }
        return out;
    }

    static Platform platform(String value) {
        if (blank(value)) {
            return null;
        }
        try {
            return Platform.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiExceptions.BadRequestException("Unknown platform filter.");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
