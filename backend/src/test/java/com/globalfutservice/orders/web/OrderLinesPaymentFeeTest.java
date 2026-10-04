package com.globalfutservice.orders.web;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.coaching.CoachingService;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.orders.DeliveryMethod;
import com.globalfutservice.fulfilment.VendorOrderLedger;
import com.globalfutservice.notify.DiscordBotClient;
import com.globalfutservice.notify.discord.DiscordVerificationService;
import com.globalfutservice.orders.OrderEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** An order paid through Payop shows the fee it was charged, not the card fee its quote carries. */
class OrderLinesPaymentFeeTest {

    private static final String BREAKDOWN = """
            {"lines":[
              {"code":"BASE","label":"Coins","amountMinor":10000,"amountFormatted":"€100.00"},
              {"code":"COUPON_DISCOUNT","label":"Coupon","amountMinor":-1000,"amountFormatted":"-€10.00"},
              {"code":"GATEWAY_FEE","label":"Payment processing (2.5%)","amountMinor":225,"amountFormatted":"€2.25"}
            ]}
            """;

    private final OrderMapper mapper = new OrderMapper(new ObjectMapper(), mock(AppProperties.class),
            mock(DiscordVerificationService.class), mock(DiscordBotClient.class), mock(CoachingService.class),
            mock(VendorOrderLedger.class));

    private static OrderEntity order() {
        return new OrderEntity("GFS-26-EUR00001", "q_1", "FC27", Sku.TRADING_SERVICE, null, null, BigDecimal.ONE,
                DeliveryMethod.PLAYER_AUCTION, Currency.EUR, 10000, 9225, BREAKDOWN);
    }

    @Test
    @DisplayName("any other way of paying: the quote's lines, card fee included, as before")
    void unchanged() {
        assertThat(mapper.lines(order())).extracting(OrderDtos.OrderLineDto::code)
                .containsExactly("BASE", "COUPON_DISCOUNT", "GATEWAY_FEE");
    }

    @Test
    @DisplayName("paid through Payop: the card fee line gives way to the fee charged, and the lines add up to the total")
    void payopFee() {
        OrderEntity order = order();
        order.recordPayopPayment(5L, 9407, """
                {"provider":"PAYOP","label":"Payment processing fee","feeMinor":407,"netMinor":9000,"totalMinor":9407}
                """);
        List<OrderDtos.OrderLineDto> lines = mapper.lines(order);
        assertThat(lines).extracting(OrderDtos.OrderLineDto::code)
                .containsExactly("BASE", "COUPON_DISCOUNT", "PAYMENT_FEE");
        assertThat(lines.get(2).label()).isEqualTo("Payment processing fee");
        assertThat(lines.get(2).amountMinor()).isEqualTo(407);
        assertThat(lines.get(2).amountFormatted()).isEqualTo("€4.07");
        assertThat(lines.stream().mapToLong(OrderDtos.OrderLineDto::amountMinor).sum())
                .isEqualTo(order.getTotalMinor());
    }
}
