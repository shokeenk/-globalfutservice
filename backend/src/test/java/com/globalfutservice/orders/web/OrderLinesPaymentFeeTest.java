package com.globalfutservice.orders.web;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.coaching.CoachingService;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.orders.DeliveryMethod;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.fulfilment.VendorOrderLedger;
import com.globalfutservice.notify.DiscordBotClient;
import com.globalfutservice.notify.discord.DiscordVerificationService;
import com.globalfutservice.orders.OrderEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * An order paid through Payop -- or with a Payop invoice open for it -- shows its method's own
 * fee, never the 2.5% card fee its quote carries. Every other way of paying keeps the card fee.
 */
class OrderLinesPaymentFeeTest {

    private static final String BREAKDOWN = """
            {"lines":[
              {"code":"BASE","label":"Coins","amountMinor":10000,"amountFormatted":"€100.00"},
              {"code":"COUPON_DISCOUNT","label":"Coupon","amountMinor":-1000,"amountFormatted":"-€10.00"},
              {"code":"GATEWAY_FEE","label":"Payment processing (2.5%)","amountMinor":225,"amountFormatted":"€2.25"}
            ],"totalMinor":9225}
            """;

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");

    private final OrderMapper mapper = new OrderMapper(new ObjectMapper(), mock(AppProperties.class),
            mock(DiscordVerificationService.class), mock(DiscordBotClient.class), mock(CoachingService.class),
            mock(VendorOrderLedger.class), mock(com.globalfutservice.orders.OrderPaymentState.class),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private static OrderEntity order(OrderStatus status) {
        OrderEntity order = new OrderEntity("GFS-26-EUR00001", "q_1", "FC27", Sku.TRADING_SERVICE, null, null,
                BigDecimal.ONE, DeliveryMethod.PLAYER_AUCTION, Currency.EUR, 10000, 9225, BREAKDOWN);
        ReflectionTestUtils.setField(order, "status", status);
        return order;
    }

    /** What the order keeps about a Payop invoice: the method, its fee, the total, and until when it can be paid. */
    private static String terms(String method, long fee, long total, Instant payableUntil) {
        return """
                {"provider":"PAYOP","methodName":"%s","feeMinor":%d,"netMinor":9000,"totalMinor":%d,"payableUntil":"%s"}
                """.formatted(method, fee, total, payableUntil);
    }

    private static long sum(List<OrderDtos.OrderLineDto> lines) {
        return lines.stream().mapToLong(OrderDtos.OrderLineDto::amountMinor).sum();
    }

    @Test
    @DisplayName("any other way of paying -- UPI, PayPal, USDT: the quote's lines, 2.5% card fee included, as before")
    void cardFee() {
        OrderEntity order = order(OrderStatus.AWAITING_PAYMENT);
        assertThat(mapper.lines(order)).extracting(OrderDtos.OrderLineDto::code)
                .containsExactly("BASE", "COUPON_DISCOUNT", "GATEWAY_FEE");
        assertThat(mapper.payableTotalMinor(order)).isEqualTo(9225);
    }

    @Test
    @DisplayName("paid through Payop: the card fee line gives way to the method's fee, named, and the lines add up to the total")
    void paid() {
        OrderEntity order = order(OrderStatus.PAID);
        order.recordPayopPayment(5L, 9407, terms("Bank transfer", 407, 9407, NOW.minusSeconds(60)), null);
        List<OrderDtos.OrderLineDto> lines = mapper.lines(order);
        assertThat(lines).extracting(OrderDtos.OrderLineDto::code)
                .containsExactly("BASE", "COUPON_DISCOUNT", "PAYMENT_FEE");
        assertThat(lines.get(2).label()).isEqualTo("Payment processing fee (Bank transfer)");
        assertThat(lines.get(2).method()).isEqualTo("Bank transfer");
        assertThat(lines.get(2).amountMinor()).isEqualTo(407);
        assertThat(lines.get(2).amountFormatted()).isEqualTo("€4.07");
        assertThat(sum(lines)).isEqualTo(order.getTotalMinor()).isEqualTo(mapper.payableTotalMinor(order));
    }

    @Test
    @DisplayName("an older Payop payment, recorded without the method's name: the fee line still replaces the card fee")
    void paidBeforeMethodNames() {
        OrderEntity order = order(OrderStatus.PAID);
        order.recordPayopPayment(5L, 9407, """
                {"provider":"PAYOP","label":"Payment processing fee","feeMinor":407,"netMinor":9000,"totalMinor":9407}
                """, null);
        List<OrderDtos.OrderLineDto> lines = mapper.lines(order);
        assertThat(lines).extracting(OrderDtos.OrderLineDto::code).containsExactly("BASE", "COUPON_DISCOUNT", "PAYMENT_FEE");
        assertThat(lines.get(2).label()).isEqualTo("Payment processing fee");
        assertThat(sum(lines)).isEqualTo(9407);
    }

    @Test
    @DisplayName("a Payop invoice open for the unpaid order: it reads as that payment -- its fee and total, no card fee")
    void invoiceOpen() {
        OrderEntity order = order(OrderStatus.AWAITING_PAYMENT);
        order.choosePayopTerms(terms("Visa / Mastercard", 312, 9312, NOW.plusSeconds(3600)));
        List<OrderDtos.OrderLineDto> lines = mapper.lines(order);
        assertThat(lines).extracting(OrderDtos.OrderLineDto::code)
                .containsExactly("BASE", "COUPON_DISCOUNT", "PAYMENT_FEE");
        assertThat(lines.get(2).label()).isEqualTo("Payment processing fee (Visa / Mastercard)");
        assertThat(mapper.payableTotalMinor(order)).isEqualTo(9312).isEqualTo(sum(lines));
        // Nothing is charged yet: the order's own total -- what UPI, PayPal and USDT are paid at -- is untouched.
        assertThat(order.getTotalMinor()).isEqualTo(9225);
    }

    @Test
    @DisplayName("the invoice can no longer be paid, or the order moved on unpaid: back to the order as placed, card fee and all")
    void invoiceLapsed() {
        OrderEntity lapsed = order(OrderStatus.AWAITING_PAYMENT);
        lapsed.choosePayopTerms(terms("Visa / Mastercard", 312, 9312, NOW));
        assertThat(mapper.lines(lapsed)).extracting(OrderDtos.OrderLineDto::code).contains("GATEWAY_FEE");
        assertThat(mapper.payableTotalMinor(lapsed)).isEqualTo(9225);

        OrderEntity abandoned = order(OrderStatus.ABANDONED);
        abandoned.choosePayopTerms(terms("Visa / Mastercard", 312, 9312, NOW.plusSeconds(3600)));
        assertThat(mapper.lines(abandoned)).extracting(OrderDtos.OrderLineDto::code).contains("GATEWAY_FEE");
        assertThat(mapper.payableTotalMinor(abandoned)).isEqualTo(9225);
    }

    @Test
    @DisplayName("a payment already made keeps its terms: a later invoice never replaces them")
    void paidTermsStay() {
        OrderEntity order = order(OrderStatus.PAID);
        order.recordPayopPayment(5L, 9407, terms("Bank transfer", 407, 9407, NOW.minusSeconds(60)), null);
        order.choosePayopTerms(terms("Visa / Mastercard", 312, 9312, NOW.plusSeconds(3600)));
        assertThat(mapper.lines(order).get(2).label()).isEqualTo("Payment processing fee (Bank transfer)");
        assertThat(mapper.payableTotalMinor(order)).isEqualTo(9407);
    }

    @Test
    @DisplayName("a breakdown already rewritten to what was charged: one fee line, never two")
    void rewrittenBreakdown() {
        OrderEntity order = order(OrderStatus.PAID);
        order.recordPayopPayment(5L, 9407, terms("Bank transfer", 407, 9407, NOW.minusSeconds(60)), """
                {"lines":[
                  {"code":"BASE","label":"Coins","amountMinor":10000,"amountFormatted":"€100.00"},
                  {"code":"COUPON_DISCOUNT","label":"Coupon","amountMinor":-1000,"amountFormatted":"-€10.00"},
                  {"code":"PAYMENT_FEE","label":"Payment processing fee (Bank transfer)","amountMinor":407,
                   "amountFormatted":"€4.07","method":"Bank transfer"}
                ],"totalMinor":9407}
                """);
        assertThat(mapper.lines(order)).extracting(OrderDtos.OrderLineDto::code)
                .containsExactly("BASE", "COUPON_DISCOUNT", "PAYMENT_FEE");
        assertThat(mapper.netLines(order)).extracting(OrderDtos.OrderLineDto::code)
                .containsExactly("BASE", "COUPON_DISCOUNT");
    }
}
