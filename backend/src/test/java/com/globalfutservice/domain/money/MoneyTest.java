package com.globalfutservice.domain.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @ParameterizedTest(name = "{0} {1} formats as {2}")
    @CsvSource({
            "INR, 70000,    ₹700.00",
            "INR, 123456789, '₹12,34,567.89'",
            "INR, 263681,   '₹2,636.81'",
            "USD, 123456789, '$1,234,567.89'",
            "INR, -25050,   -₹250.50",
            "INR, 0,        ₹0.00",
    })
    @DisplayName("formats with the grouping the customer expects")
    void formatting(String currency, long minor, String expected) {
        assertThat(Money.ofMinor(minor, Currency.valueOf(currency)).format()).isEqualTo(expected);
    }

    @Test
    @DisplayName("major-unit construction rounds half up, once")
    void construction_rounds() {
        assertThat(Money.ofMajor("700", Currency.INR).minor()).isEqualTo(70_000L);
        assertThat(Money.ofMajor("0.005", Currency.INR).minor()).isEqualTo(1L);
        assertThat(Money.ofMajor("0.004", Currency.INR).minor()).isEqualTo(0L);
    }

    @Test
    @DisplayName("arithmetic is exact")
    void arithmetic() {
        Money a = Money.ofMajor("0.10", Currency.INR);
        Money b = Money.ofMajor("0.20", Currency.INR);
        assertThat(a.plus(b)).isEqualTo(Money.ofMajor("0.30", Currency.INR));
        assertThat(a.minus(b).minor()).isEqualTo(-10L);
        assertThat(a.negated().negated()).isEqualTo(a);
    }

    @Test
    @DisplayName("mixing currencies throws rather than silently coercing")
    void currency_mismatch() {
        assertThatThrownBy(() -> Money.ofMinor(1, Currency.INR).plus(Money.ofMinor(1, Currency.USD)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");
    }

    @Test
    @DisplayName("overflow is detected, not wrapped around")
    void overflow() {
        Money huge = Money.ofMinor(Long.MAX_VALUE, Currency.INR);
        assertThatThrownBy(() -> huge.plus(Money.ofMinor(1, Currency.INR)))
                .isInstanceOf(ArithmeticException.class);
    }
}
