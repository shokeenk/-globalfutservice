package com.globalfutservice.orders.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The settlement rules the client decided: the tier difference, scaled by the discount
 * actually given on the service, the payment fee never refunded, rounded to the minor unit
 * and never more than was paid; points taken back in proportion; and a full refund after a
 * settlement paying back only what is left.
 */
class SettlementCalculatorTest {

    @Test
    @DisplayName("15 wins at ₹4,000, 14 achieved (₹3,500), 5% coupon: ₹500 × 0.95 = ₹475")
    void clientsExample() {
        // Paid for the service: ₹4,000 less the 5% coupon. The 2.5% fee (₹95) is not in it.
        assertThat(SettlementCalculator.settlement(400_000, 350_000, 380_000)).isEqualTo(47_500);
    }

    @Test
    @DisplayName("the payment fee is never part of it: the same order paid with a bigger fee settles the same")
    void feeExcluded() {
        // Whatever the fee was -- 2.5% or a Payop method's -- what is passed is the service alone.
        long withoutFee = SettlementCalculator.settlement(400_000, 350_000, 380_000);
        assertThat(withoutFee).isEqualTo(47_500);
        assertThat(SettlementCalculator.settlement(400_000, 350_000, 380_000 + 9_500))
                .as("had the fee been counted, it would be more")
                .isGreaterThan(withoutFee);
    }

    @Test
    @DisplayName("no discount: the full tier difference")
    void noDiscount() {
        assertThat(SettlementCalculator.settlement(400_000, 350_000, 400_000)).isEqualTo(50_000);
    }

    @Test
    @DisplayName("a euro order: €36.99 ordered, €32.49 achieved, €3.70 off: rounded half up to the cent")
    void euros() {
        // €4.50 × (33.29 ÷ 36.99) = €4.0498... -> €4.05
        assertThat(SettlementCalculator.settlement(3_699, 3_249, 3_329)).isEqualTo(405);
    }

    @Test
    @DisplayName("every win achieved, or a tier priced at the ordered one: nothing owed")
    void nothingOwed() {
        assertThat(SettlementCalculator.settlement(400_000, 400_000, 380_000)).isZero();
        assertThat(SettlementCalculator.settlement(400_000, 450_000, 380_000)).isZero();
    }

    @Test
    @DisplayName("never more than was paid for the service")
    void capped() {
        // Paid almost nothing in money -- points covered most of it: the settlement follows.
        assertThat(SettlementCalculator.settlement(400_000, 120_000, 1_000)).isEqualTo(700);
        assertThat(SettlementCalculator.settlement(400_000, 0, 380_000)).isEqualTo(380_000);
        assertThat(SettlementCalculator.settlement(400_000, 350_000, 0)).isZero();
    }

    @Test
    @DisplayName("below the lowest tier: any amount from zero to what was paid, and no more")
    void manual() {
        assertThat(SettlementCalculator.manual(150_000, 380_000)).isEqualTo(150_000);
        assertThat(SettlementCalculator.manual(380_000, 380_000)).isEqualTo(380_000);
        assertThat(SettlementCalculator.manual(0, 380_000)).isZero();
        assertThatThrownBy(() -> SettlementCalculator.manual(380_001, 380_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettlementCalculator.manual(-1, 380_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("points taken back in proportion, to the nearest whole point, never more than earned")
    void points() {
        // 20 points × ₹475 / ₹3,800 = 2.5 -> 3
        assertThat(SettlementCalculator.pointsRemoved(20, 47_500, 380_000)).isEqualTo(3);
        // 40 × 100 / 380 = 10.5 -> 11
        assertThat(SettlementCalculator.pointsRemoved(40, 10_000, 38_000)).isEqualTo(11);
        assertThat(SettlementCalculator.pointsRemoved(20, 380_000, 380_000)).isEqualTo(20);
        assertThat(SettlementCalculator.pointsRemoved(0, 47_500, 380_000)).isZero();
        assertThat(SettlementCalculator.pointsRemoved(20, 0, 380_000)).isZero();
    }

    @Test
    @DisplayName("a refund in money only up to what was paid in money for the service")
    void refundAllowed() {
        assertThat(SettlementCalculator.refundAllowed(47_500, 380_000, 0)).isTrue();
        assertThat(SettlementCalculator.refundAllowed(47_500, 380_000, 332_500)).isTrue();
        assertThat(SettlementCalculator.refundAllowed(47_500, 380_000, 332_501)).isFalse();
    }

    @Test
    @DisplayName("a full refund after a settlement: the total less what was settled, credit coming back as credit")
    void fullRefundAfterSettlement() {
        assertThat(SettlementCalculator.moneyBackOnFullRefund(389_500, 47_500)).isEqualTo(342_000);
        assertThat(SettlementCalculator.moneyBackOnFullRefund(389_500, 0)).isEqualTo(389_500);
        assertThat(SettlementCalculator.moneyBackOnFullRefund(20_000, 47_500)).isZero();

        assertThat(SettlementCalculator.creditBackOnFullRefund(0, 389_500, 47_500)).isZero();
        assertThat(SettlementCalculator.creditBackOnFullRefund(100_000, 289_500, 47_500)).isEqualTo(100_000);
        // Settled more than the money paid: the rest of the settlement comes off the credit returned.
        assertThat(SettlementCalculator.creditBackOnFullRefund(380_000, 20_000, 47_500)).isEqualTo(352_500);
    }
}
