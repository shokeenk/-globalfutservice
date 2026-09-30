package com.globalfutservice.orders;

import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.orders.DeliveryMethod;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Coins have one method, which the checkout shows rather than offers; the server decides it.
 * Boosting and coaching resolve exactly as before.
 */
class DeliveryMethodResolutionTest {

    private static final DeliveryMethod CONFIGURED = DeliveryMethod.PLAYER_AUCTION;

    private static DeliveryMethod resolve(String requested, Sku sku) {
        return OrderService.resolveDeliveryMethod(requested, sku, CONFIGURED);
    }

    @Test
    @DisplayName("a coin order is always sent the configured way, whatever the request asks for")
    void coinsIgnoreTheRequest() {
        assertThat(resolve(null, Sku.TRADING_SERVICE)).isEqualTo(CONFIGURED);
        assertThat(resolve("COMFORT_TRADE", Sku.TRADING_SERVICE)).isEqualTo(CONFIGURED);
        assertThat(resolve("SCHEDULED_SESSION", Sku.TRADING_SERVICE)).isEqualTo(CONFIGURED);
        assertThat(resolve("targetedSnipe", Sku.TRADING_SERVICE)).isEqualTo(CONFIGURED);
        assertThat(OrderService.resolveDeliveryMethod("PLAYER_AUCTION", Sku.TRADING_SERVICE, DeliveryMethod.COMFORT_TRADE))
                .isEqualTo(DeliveryMethod.COMFORT_TRADE);
    }

    @Test
    @DisplayName("boosting, coaching and cards are unchanged")
    void othersUnchanged() {
        assertThat(resolve("COMFORT_TRADE", Sku.BOOST_CHAMPS)).isEqualTo(DeliveryMethod.COMFORT_TRADE);
        assertThat(resolve(null, Sku.BOOST_RIVALS)).isEqualTo(CONFIGURED);
        assertThatThrownBy(() -> resolve("nonsense", Sku.BOOST_CHAMPS))
                .isInstanceOf(ApiExceptions.BadRequestException.class);
        assertThat(resolve("COMFORT_TRADE", Sku.COACHING)).isEqualTo(DeliveryMethod.SCHEDULED_SESSION);
        assertThat(resolve("COMFORT_TRADE", Sku.CARDS)).isEqualTo(DeliveryMethod.PLAYER_AUCTION);
    }
}
