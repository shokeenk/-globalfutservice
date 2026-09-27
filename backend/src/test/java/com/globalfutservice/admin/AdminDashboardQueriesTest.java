package com.globalfutservice.admin;

import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.orders.OrderEventRepository;
import com.globalfutservice.orders.OrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The dashboard's days are India days, and its revenue is never added across currencies. */
class AdminDashboardQueriesTest {

    /** 10:00 on 27 Sep in India. */
    private static final Instant NOW = Instant.parse("2026-09-27T04:30:00Z");

    @Test
    @DisplayName("today starts at midnight in India; yesterday is compared up to the same time")
    void days() {
        AdminDashboardQueries.Days days = AdminDashboardQueries.Days.at(NOW);
        assertThat(days.todayStarts()).isEqualTo(Instant.parse("2026-09-26T18:30:00Z"));
        assertThat(days.yesterdayStarts()).isEqualTo(Instant.parse("2026-09-25T18:30:00Z"));
        assertThat(days.yesterdaySameTime()).isEqualTo(Instant.parse("2026-09-26T04:30:00Z"));
    }

    @Test
    @DisplayName("revenue comes per currency, rupees first and always present")
    void revenuePerCurrency() {
        OrderRepository orders = mock(OrderRepository.class);
        AdminDashboardQueries.Days days = AdminDashboardQueries.Days.at(NOW);
        when(orders.revenueByCurrency(eq(days.todayStarts()), eq(NOW)))
                .thenReturn(List.<Object[]>of(new Object[]{Currency.GBP, 2399L}));
        when(orders.revenueByCurrency(eq(days.yesterdayStarts()), eq(days.yesterdaySameTime())))
                .thenReturn(List.<Object[]>of(new Object[]{Currency.INR, 190000L}));
        AdminDashboardQueries queries = new AdminDashboardQueries(orders, mock(OrderEventRepository.class),
                mock(AdminOrderQueries.class), Clock.fixed(NOW, ZoneOffset.UTC));

        List<AdminDashboardQueries.CurrencyRevenue> revenue = queries.revenueToday();

        assertThat(revenue).extracting(AdminDashboardQueries.CurrencyRevenue::currency).containsExactly("INR", "GBP");
        assertThat(revenue.get(0).todayMinor()).isZero();
        assertThat(revenue.get(0).yesterdayMinor()).isEqualTo(190000L);
        assertThat(revenue.get(0).yesterdayFormatted()).isEqualTo("₹1,900.00");
        assertThat(revenue.get(1).todayFormatted()).isEqualTo("£23.99");
    }

    @Test
    @DisplayName("pending means paid and not yet delivered: never unpaid, never finished")
    void pending() {
        assertThat(AdminDashboardQueries.PENDING).extracting(Enum::name).containsExactlyInAnyOrder(
                "PAID", "CREDENTIALS_PENDING", "READY_FOR_DELIVERY", "IN_PROGRESS", "ON_HOLD");
    }
}
