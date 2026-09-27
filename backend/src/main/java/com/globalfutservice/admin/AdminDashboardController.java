package com.globalfutservice.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The console's dashboard.
 *
 * <p>Two calls, split by who may see them. The counts and the two lists are an operator's
 * as much as an admin's: they are the queue, summarised. Today's revenue is an admin's
 * figure, as the 30-day one on Analytics is, so it has an endpoint of its own with its own
 * role check. Hiding the card for an operator would not be enough if the number arrived in
 * the same response.
 */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@Tag(name = "Admin — dashboard", description = "Today at a glance")
public class AdminDashboardController {

    private final AdminDashboardQueries queries;

    public AdminDashboardController(AdminDashboardQueries queries) {
        this.queries = queries;
    }

    @GetMapping
    @Operation(summary = "Today's counts, the newest orders and the latest activity. No money.")
    public ResponseEntity<AdminDashboardQueries.Dashboard> dashboard() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(queries.dashboard());
    }

    @GetMapping("/revenue")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Today's revenue per currency, against yesterday at the same time (admin)",
            description = "Delivered and completed orders placed today: the same rule as the "
                    + "30-day figure on Analytics. Not summed across currencies.")
    public ResponseEntity<List<AdminDashboardQueries.CurrencyRevenue>> revenue() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(queries.revenueToday());
    }
}
