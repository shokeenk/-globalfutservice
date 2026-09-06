package com.globalfutservice.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which account a customer is told to pay.
 *
 * <p>Coin orders and everything else go to different UPI accounts. That split is invisible
 * -- both destinations are valid, both render identically, and a customer paying the wrong
 * one still sees a QR code and a name and a working payment. The mistake only surfaces
 * later, as money in an account nobody is reconciling against that order, which is why the
 * rule is asserted here rather than left to the one line of code that implements it.
 */
class ManualPaymentsConfigTest {

    private static final String COINS = "coins@bank";
    private static final String COINS_NAME = "Coins Account";
    private static final String SERVICES = "services@bank";
    private static final String SERVICES_NAME = "Services Account";

    private static AppProperties.ManualPayments configured() {
        return new AppProperties.ManualPayments(
                COINS, COINS_NAME, SERVICES, SERVICES_NAME,
                "pay@example.com", "https://paypal.example/x", "TWALLETADDRESS");
    }

    @Nested
    @DisplayName("choosing the UPI account")
    class Routing {

        @Test
        @DisplayName("coin orders go to the coins account")
        void coinsGoToCoinsAccount() {
            assertThat(configured().upiFor("TRADING_SERVICE")).isEqualTo(COINS);
            assertThat(configured().upiNameFor("TRADING_SERVICE")).isEqualTo(COINS_NAME);
        }

        @Test
        @DisplayName("boosting and coaching go to the services account")
        void everythingElseGoesToServices() {
            for (String sku : new String[]{"CHAMPS_BOOSTING", "RIVALS_BOOSTING", "COACHING"}) {
                assertThat(configured().upiFor(sku))
                        .as("destination for %s", sku)
                        .isEqualTo(SERVICES);
                assertThat(configured().upiNameFor(sku))
                        .as("account name for %s", sku)
                        .isEqualTo(SERVICES_NAME);
            }
        }

        @Test
        @DisplayName("the sku is matched without regard to case")
        void skuMatchIsCaseInsensitive() {
            assertThat(configured().upiFor("trading_service")).isEqualTo(COINS);
        }

        @Test
        @DisplayName("an unknown sku falls to the services account, not to nothing")
        void unknownSkuFallsToServices() {
            /*
             * Deliberate: a sku added later should collect money into the general account
             * rather than silently offer no UPI at all. Getting paid into the wrong-ish
             * account is a reconciliation nuisance; offering an Indian customer no
             * domestic payment method is a lost sale nobody would notice.
             */
            assertThat(configured().upiFor("SOMETHING_NEW")).isEqualTo(SERVICES);
        }
    }

    @Nested
    @DisplayName("when a destination is not configured")
    class Unconfigured {

        @Test
        @DisplayName("blank is treated as absent, so no half-configured address is shown")
        void blankIsAbsent() {
            AppProperties.ManualPayments partial = new AppProperties.ManualPayments(
                    "  ", "", SERVICES, SERVICES_NAME, null, null, "");

            // A blank env var is what an unset one looks like after substitution. Returning
            // it as a destination would render an empty address under a QR code, which
            // reads as a page that failed to load rather than a method that is off.
            assertThat(partial.upiFor("TRADING_SERVICE")).isNull();
            assertThat(partial.upiNameFor("TRADING_SERVICE")).isNull();
            assertThat(partial.upiFor("COACHING")).isEqualTo(SERVICES);
        }

        @Test
        @DisplayName("null is treated as absent")
        void nullIsAbsent() {
            AppProperties.ManualPayments none = new AppProperties.ManualPayments(
                    null, null, null, null, null, null, null);

            assertThat(none.upiFor("TRADING_SERVICE")).isNull();
            assertThat(none.upiFor("COACHING")).isNull();
        }
    }
}
