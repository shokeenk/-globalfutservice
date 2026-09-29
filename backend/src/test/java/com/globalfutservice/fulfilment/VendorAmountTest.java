package com.globalfutservice.fulfilment;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.orders.OrderEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Coins in millions to the vendor's whole thousands: exact, or not sent. */
class VendorAmountTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("converts exactly: 500,000 coins is 500, 2M is 2000, 1.5M is 1500")
    void exact() {
        assertThat(VendorAmount.thousandsExact(new BigDecimal("0.5"))).isEqualTo(500);
        assertThat(VendorAmount.thousandsExact(new BigDecimal("0.50"))).isEqualTo(500);
        assertThat(VendorAmount.thousandsExact(new BigDecimal("2"))).isEqualTo(2000);
        assertThat(VendorAmount.thousandsExact(new BigDecimal("1.500"))).isEqualTo(1500);
        assertThat(VendorAmount.thousandsExact(new BigDecimal("0.001"))).isEqualTo(1);
    }

    @Test
    @DisplayName("refuses anything that is not a whole number of thousands, or not positive")
    void inexact() {
        assertThatThrownBy(() -> VendorAmount.thousandsExact(new BigDecimal("0.0005")))
                .isInstanceOf(VendorAmount.InvalidAmountException.class);
        assertThatThrownBy(() -> VendorAmount.thousandsExact(new BigDecimal("1.2345")))
                .isInstanceOf(VendorAmount.InvalidAmountException.class);
        assertThatThrownBy(() -> VendorAmount.thousandsExact(BigDecimal.ZERO))
                .isInstanceOf(VendorAmount.InvalidAmountException.class);
        assertThatThrownBy(() -> VendorAmount.thousandsExact(new BigDecimal("-1")))
                .isInstanceOf(VendorAmount.InvalidAmountException.class);
        assertThatThrownBy(() -> VendorAmount.thousandsExact(null))
                .isInstanceOf(VendorAmount.InvalidAmountException.class);
    }

    private OrderEntity order(String quantity, String breakdown) {
        OrderEntity o = mock(OrderEntity.class);
        when(o.getQuantity()).thenReturn(new BigDecimal(quantity));
        when(o.getPriceBreakdown()).thenReturn(breakdown);
        return o;
    }

    @Test
    @DisplayName("the order's amount must be the amount in the quote the customer paid for")
    void matchesPaidQuote() {
        assertThat(VendorAmount.forOrder(order("0.10", "{\"sku\":\"TRADING_SERVICE\",\"quantity\":0.1}"), mapper))
                .isEqualTo(100);

        assertThatThrownBy(() -> VendorAmount.forOrder(order("2.00", "{\"quantity\":1.0}"), mapper))
                .isInstanceOf(VendorAmount.InvalidAmountException.class)
                .hasMessageContaining("differs from the quote");
        assertThatThrownBy(() -> VendorAmount.forOrder(order("1.00", "{\"sku\":\"TRADING_SERVICE\"}"), mapper))
                .isInstanceOf(VendorAmount.InvalidAmountException.class);
        assertThatThrownBy(() -> VendorAmount.forOrder(order("1.00", "not json"), mapper))
                .isInstanceOf(VendorAmount.InvalidAmountException.class);
    }
}
