package com.globalfutservice.orders;

import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.loyalty.LoyaltyService;
import com.globalfutservice.orders.result.OrderResultEntity;
import com.globalfutservice.orders.result.OrderResultRepository;
import com.globalfutservice.storecredit.StoreCreditService;
import com.globalfutservice.web.ApiExceptions;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * One settlement per order. An order already settled -- as points the old way, as GFS
 * Store Credit, or as a refund of a Champions settlement -- cannot be settled again, and the
 * admin is told what it was settled with and when, rather than meeting a database error.
 */
@Component
public class Settlements {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final LoyaltyService points;
    private final StoreCreditService credit;
    private final OrderResultRepository results;

    public Settlements(LoyaltyService points, StoreCreditService credit, OrderResultRepository results) {
        this.points = points;
        this.credit = credit;
        this.results = results;
    }

    /** What the order was already settled with, in words for the admin; empty when it was not. */
    public Optional<String> existing(Long orderId) {
        if (orderId == null) {
            return Optional.empty();
        }
        Optional<String> asPoints = points.pointsCreditOn(orderId)
                .map(e -> "Already settled on " + day(e.getCreatedAt()) + ": "
                        + String.format(Locale.ENGLISH, "%,d", e.getAmount()) + " points");
        if (asPoints.isPresent()) {
            return asPoints;
        }
        Optional<String> asCredit = credit.issuedOn(orderId)
                .map(e -> "Already settled on " + day(e.getCreatedAt()) + ": "
                        + money(e.getAmountMinor(), e.getCurrency()) + " GFS Store Credit");
        if (asCredit.isPresent()) {
            return asCredit;
        }
        return results.findById(orderId)
                .filter(r -> r.getStatus() == OrderResultEntity.Status.PAID)
                .map(r -> "Already settled on " + day(r.getSettledAt()) + ": "
                        + money(r.getSettlementMinor(), r.getCurrency()) + " refund");
    }

    /** Refuses a second settlement with a 409 that says what the first one was. */
    public void requireNone(Long orderId) {
        existing(orderId).ifPresent(said -> {
            throw new ApiExceptions.ConflictException("already_settled", said + ".");
        });
    }

    private static String day(Instant at) {
        return at == null ? "an earlier date" : DAY.format(at.atZone(BUSINESS_ZONE));
    }

    private static String money(long minor, Currency currency) {
        return Money.ofMinor(minor, currency).format();
    }
}
