package com.globalfutservice.admin;

import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.orders.OrderRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Figures about the business, as opposed to the work queue.
 *
 * <p>ADMIN only, on the class, on top of the URL rule that already requires OPERATOR for
 * everything under {@code /api/v1/admin}. Revenue used to ride along in the order queue's
 * stats, which every operator's console fetches every twenty seconds; hiding the figure
 * in the interface would not have stopped an operator reading it from that response.
 * Moving it here, behind its own role check, is what makes it admin-only.
 */
@RestController
@RequestMapping("/api/v1/admin/analytics")
@Tag(name = "Admin — analytics", description = "Business figures, for admins")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAnalyticsController {

    private final OrderRepository orders;

    public AdminAnalyticsController(OrderRepository orders) {
        this.orders = orders;
    }

    /**
     * Revenue over the last thirty days, exactly as the order queue used to report it.
     *
     * <p>Delivered and completed orders placed in the last thirty days, including those
     * still inside their guarantee window. The query and the window are unchanged from
     * the stats endpoint they came from; see {@link OrderRepository#revenueSince}.
     */
    @GetMapping("/revenue")
    @Operation(summary = "Revenue, last 30 days")
    public ResponseEntity<RevenueDto> revenue() {
        Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);
        long revenue = orders.revenueSince(thirtyDaysAgo);
        return ResponseEntity.ok(new RevenueDto(revenue, Money.ofMinor(revenue, Currency.INR).format()));
    }

    /** Named as they were in the order queue's stats, so the figure reads the same. */
    public record RevenueDto(long revenueLast30dMinor, String revenueLast30dFormatted) {
    }
}
