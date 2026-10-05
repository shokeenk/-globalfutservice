package com.globalfutservice.payments.payop;

import java.math.BigDecimal;

import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.payments.payop.PayopFeeCalculator.FeeQuote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayopFeeCalculatorTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    @Test
    @DisplayName("grossed up so the client keeps the full price: (net + fixed) / (1 - %), rounded up")
    void grossUp() {
        // 100.00 EUR at 0.30 EUR + 4.0%: (10000 + 30) / 0.96 = 10447.916... -> 10448.
        FeeQuote q = PayopFeeCalculator.quote(10_000, Currency.EUR, d("0.30"), d("4.0"), BigDecimal.ONE);
        assertThat(q.totalMinor()).isEqualTo(10_448);
        assertThat(q.feeMinor()).isEqualTo(448);
        assertThat(q.netMinor() + q.feeMinor()).isEqualTo(q.totalMinor());
        // What Payop keeps of 104.48 at that rate, and what reaches the client:
        BigDecimal payopTakes = d("104.48").multiply(d("0.04")).add(d("0.30"));
        assertThat(d("104.48").subtract(payopTakes)).isGreaterThanOrEqualTo(d("100.00"));
    }

    @Test
    @DisplayName("the fixed EUR part is converted at the given rate before grossing up")
    void fixedPartConverted() {
        // 50.00 USD, 2.00 EUR + 3.0% at 1.1225 USD/EUR: (5000 + 224.5) / 0.97 = 5386.08... -> 5387.
        FeeQuote usd = PayopFeeCalculator.quote(5_000, Currency.USD, d("2.00"), d("3.0"), d("1.1225"));
        assertThat(usd.totalMinor()).isEqualTo(5_387);
        assertThat(usd.feeMinor()).isEqualTo(387);
        // 50.00 GBP, 0.30 EUR + 2.4% at 0.85033 GBP/EUR: (5000 + 25.5099) / 0.976 = 5149.08... -> 5150.
        FeeQuote gbp = PayopFeeCalculator.quote(5_000, Currency.GBP, d("0.30"), d("2.4"), d("0.85033"));
        assertThat(gbp.totalMinor()).isEqualTo(5_150);
    }

    @Test
    @DisplayName("always rounds up, never down, even a hair above a whole cent")
    void roundsUp() {
        // (1000 + 0) / 0.99 = 1010.1010... -> 1011, not 1010.
        assertThat(PayopFeeCalculator.quote(1_000, Currency.EUR, d("0"), d("1.0"), BigDecimal.ONE).totalMinor())
                .isEqualTo(1_011);
        // An exact result stays exact: (1000 + 0) / 0.5 = 2000.
        assertThat(PayopFeeCalculator.quote(1_000, Currency.EUR, d("0"), d("50"), BigDecimal.ONE).totalMinor())
                .isEqualTo(2_000);
        // No fee at all is no fee.
        assertThat(PayopFeeCalculator.quote(1_000, Currency.EUR, d("0"), d("0"), BigDecimal.ONE).feeMinor())
                .isZero();
    }

    @Test
    @DisplayName("Crypto Payment, 0 EUR + 1.0%, and the costliest fixed part, 2.50 EUR + 2.5%")
    void tableExtremes() {
        assertThat(PayopFeeCalculator.quote(2_000, Currency.USD, d("0.00"), d("1.0"), d("1.1225")).totalMinor())
                .isEqualTo(2_021); // 2000 / 0.99 = 2020.20... -> 2021
        // (2000 + 280.625) / 0.975 = 2339.10... -> 2340
        assertThat(PayopFeeCalculator.quote(2_000, Currency.USD, d("2.50"), d("2.5"), d("1.1225")).totalMinor())
                .isEqualTo(2_340);
    }

    @Test
    @DisplayName("the amount Payop is sent has exactly the currency's decimals")
    void amountString() {
        assertThat(PayopFeeCalculator.amount(10_448, Currency.EUR)).isEqualTo("104.48");
        assertThat(PayopFeeCalculator.amount(1_230, Currency.USD)).isEqualTo("12.30");
        assertThat(PayopFeeCalculator.amount(5, Currency.GBP)).isEqualTo("0.05");
    }

    @Test
    @DisplayName("refuses what cannot be a fee")
    void refuses() {
        assertThatThrownBy(() -> PayopFeeCalculator.quote(0, Currency.EUR, d("0.30"), d("4"), BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PayopFeeCalculator.quote(100, Currency.EUR, d("0.30"), d("100"), BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PayopFeeCalculator.quote(100, Currency.EUR, d("0.30"), d("4"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
