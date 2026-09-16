package com.globalfutservice.orders;

import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.orders.DeliveryMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An order has two names, and they are not interchangeable.
 *
 * <p>The customer's name is the one on the site: they pressed "Buy coins", so the
 * tracking page, the receipt and the order history say Buy Coins. The processor's name is
 * the line a risk reviewer reads, and "sale of virtual currency" is on the prohibited list
 * of most processors, Razorpay included. The business does not sell currency -- it sells
 * the trading done on the customer's own account -- and the gateway line has to keep
 * saying so however the storefront labels the button.
 *
 * <p>Both describe the same order down to the platform and the amount, taken from the one
 * frozen label, so they cannot drift into describing different ones.
 */
class OrderNamingTest {

    @Test
    @DisplayName("a coin order is Buy Coins to the customer")
    void customerSeesBuyCoins() {
        assertThat(OrderService.describe(coinOrder(null))).isEqualTo("Buy Coins \u2014 3M (PC)");
    }

    @Test
    @DisplayName("the payment processor is told a trading service, with the same detail")
    void gatewaySeesTradingService() {
        assertThat(OrderService.gatewayDescription(coinOrder("Buy Coins \u2014 3M (PC)")))
                .isEqualTo("FC coin trading service \u2014 3M (PC)")
                .doesNotContain("Buy Coins");
    }

    @Test
    @DisplayName("every other service is named the same way to both")
    void otherSkusAreNamedOnce() {
        OrderEntity coaching = new OrderEntity(
                "GFS-26-TESTREF3", "quote-3", "FC26", Sku.COACHING, null,
                "SINGLE_SESSION", BigDecimal.ONE, DeliveryMethod.SCHEDULED_SESSION,
                Currency.INR, 100000L, 102500L, "{}");
        coaching.setServiceLabel("FUT Classes \u2014 Single session");

        assertThat(OrderService.gatewayDescription(coaching))
                .isEqualTo("FUT Classes \u2014 Single session");
    }

    private static OrderEntity coinOrder(String storedLabel) {
        OrderEntity order = new OrderEntity(
                "GFS-26-TESTREF2", "quote-2", "FC26", Sku.TRADING_SERVICE, Platform.PC,
                null, new BigDecimal("3"), DeliveryMethod.PLAYER_AUCTION,
                Currency.INR, 210000L, 215250L, "{}");
        order.setServiceLabel(storedLabel);
        return order;
    }
}
