package com.globalfutservice.orders;

import com.globalfutservice.domain.catalog.PcLauncher;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.orders.DeliveryMethod;
import com.globalfutservice.orders.web.OrderDtos;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where a boosting order is played, and why the order refuses to exist without it.
 *
 * <p>A booster has to sign in to the account and play the games. Until now a boosting
 * order carried no platform at all: the tier costs the same everywhere, so nothing in the
 * pricing needed one, and the question was asked on Discord after the customer had paid.
 * That is a delay on every order and a dead end on the ones where nobody answers.
 *
 * <p>The PC launcher is the part that is easy to miss. One EA account opened through
 * Steam and through the EA app is two different sign-ins, so "PC" alone does not tell the
 * booster how to get in.
 */
class BoostingPlatformTest {

    @Test
    @DisplayName("a boosting order with no platform is refused before it is saved")
    void platformIsRequired() {
        OrderEntity order = boostingOrder();

        assertThatThrownBy(() -> OrderService.applyBoostingDetails(order, request(null, null)))
                .isInstanceOf(ApiExceptions.BadRequestException.class)
                .hasMessageContaining("platform");
        assertThat(order.getPlatform()).isNull();
    }

    @Test
    @DisplayName("PC without a launcher is refused: the booster would not know how to sign in")
    void launcherIsRequiredOnPc() {
        OrderEntity order = boostingOrder();

        assertThatThrownBy(() -> OrderService.applyBoostingDetails(order, request("PC", null)))
                .isInstanceOf(ApiExceptions.BadRequestException.class)
                .hasMessageContaining("launcher");
    }

    @Test
    @DisplayName("PC keeps the launcher, and both reach the label the operator reads")
    void pcStoresLauncher() {
        OrderEntity order = boostingOrder();

        OrderService.applyBoostingDetails(order, request("PC", "STEAM"));

        assertThat(order.getPlatform()).isEqualTo(Platform.PC);
        assertThat(order.getPcLauncher()).isEqualTo(PcLauncher.STEAM);
        assertThat(order.getServiceLabel()).isEqualTo("Champs Boosting \u2014 15 wins (PC \u00b7 Steam)");
    }

    @Test
    @DisplayName("a console order stores no launcher, even when one was sent")
    void consoleHasNoLauncher() {
        OrderEntity order = boostingOrder();

        OrderService.applyBoostingDetails(order, request("PLAYSTATION", "STEAM"));

        assertThat(order.getPlatform()).isEqualTo(Platform.PLAYSTATION);
        assertThat(order.getPcLauncher()).isNull();
        assertThat(order.getServiceLabel()).isEqualTo("Champs Boosting \u2014 15 wins (PlayStation)");
    }

    private static OrderEntity boostingOrder() {
        OrderEntity order = new OrderEntity(
                "GFS-26-TESTREF1", "quote-1", "FC26", Sku.BOOST_CHAMPS, null,
                "WINS_15", BigDecimal.ONE, DeliveryMethod.COMFORT_TRADE,
                Currency.INR, 355000L, 363875L, "{}");
        order.setServiceLabel("Champs Boosting \u2014 15 wins");
        return order;
    }

    /** Everything else on the request is what a boosting checkout actually sends: nothing. */
    private static OrderDtos.CreateOrderRequest request(String platform, String launcher) {
        return new OrderDtos.CreateOrderRequest(
                null, "player@example.test", null, null, null, null, null, null,
                null, null, null, platform, launcher, true);
    }
}
