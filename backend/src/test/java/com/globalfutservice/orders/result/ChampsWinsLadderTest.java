package com.globalfutservice.orders.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.catalog.RateCardEntity;
import com.globalfutservice.catalog.RateCardRepository;
import com.globalfutservice.domain.catalog.PriceUnit;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.orders.DeliveryMethod;
import com.globalfutservice.orders.OrderEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Champions ladder: the tiers on sale when the order was priced, placed by their wins,
 * and what an order paid for its service read off its frozen breakdown.
 */
class ChampsWinsLadderTest {

    private static final Instant PRICED = Instant.parse("2026-10-08T02:54:00Z");

    private static RateCardEntity tier(String variant, String label, long price, int sort) {
        return new RateCardEntity("FC26", Sku.BOOST_CHAMPS, null, variant, Currency.INR, PriceUnit.FLAT, price,
                null, null, null, label, sort, null);
    }

    private static OrderEntity order(String variant, String breakdown) {
        return new OrderEntity("GFS-26-LADDER01", "q_ladder", "FC26", Sku.BOOST_CHAMPS, null, variant,
                BigDecimal.ONE, DeliveryMethod.COMFORT_TRADE, Currency.INR, 400_000, 389_500, breakdown);
    }

    private static Ladder ladder(String variant, List<RateCardEntity> onSale) {
        RateCardRepository rates = mock(RateCardRepository.class);
        when(rates.findOnSaleAt(eq("FC26"), eq(Sku.BOOST_CHAMPS), eq(Currency.INR), any())).thenReturn(onSale);
        return new ChampsWinsLadder(rates).forOrder(order(variant, "{}"), PRICED).orElse(null);
    }

    private static final List<RateCardEntity> SEASON = List.of(
            tier("WINS_9", "9 wins · Champion II", 120_000, 1),
            tier("WINS_10", "10 wins · Champion I", 155_000, 2),
            tier("WINS_11", "11 wins · Elite V", 190_000, 3),
            tier("WINS_12", "12 wins · Elite IV", 210_000, 4),
            tier("WINS_13", "13 wins · Elite III", 260_000, 5),
            tier("WINS_14", "14 wins · Elite II", 350_000, 6),
            tier("WINS_15", "15 wins · Elite I", 400_000, 7),
            tier("WINS_EXTRA_8", "+8 extra wins", 170_000, 8));

    @Test
    @DisplayName("ordered 15 wins: 9 to 15 to choose from, each at its price, extra wins not a level")
    void fromTheLowestTierToTheOrderedOne() {
        Ladder l = ladder("WINS_15", SEASON);
        assertThat(l.target()).isEqualTo(15);
        assertThat(l.lowest()).isEqualTo(9);
        assertThat(l.orderedPriceMinor()).isEqualTo(400_000);
        assertThat(l.options()).containsExactly(9, 10, 11, 12, 13, 14, 15);
        assertThat(l.rungFor(14).orElseThrow().priceMinor()).isEqualTo(350_000);
        assertThat(l.rungFor(8)).as("below the lowest tier").isEmpty();
    }

    @Test
    @DisplayName("ordered 12: nothing above the order is offered")
    void notAboveTheOrder() {
        assertThat(ladder("WINS_12", SEASON).options()).containsExactly(9, 10, 11, 12);
    }

    @Test
    @DisplayName("a tier added on Service Listings is placed by its code or its name")
    void addedTiers() {
        assertThat(ChampsWinsLadder.winsOf("WINS_15", null)).isEqualTo(15);
        assertThat(ChampsWinsLadder.winsOf("16_WINS_ELITE_I", "16 wins · Elite I+")).isEqualTo(16);
        assertThat(ChampsWinsLadder.winsOf("SPECIAL", "17 Wins · Top 200")).isEqualTo(17);
        assertThat(ChampsWinsLadder.winsOf("WINS_EXTRA_8", "+8 extra wins")).isNull();
        assertThat(ChampsWinsLadder.winsOf("FAST_PUSH", "Fast push")).isNull();
    }

    @Test
    @DisplayName("a level with no tier of its own is priced at the tier below it")
    void gaps() {
        Ladder l = ladder("WINS_15", List.of(tier("WINS_9", "9 wins", 120_000, 1), tier("WINS_12", "12 wins", 210_000, 2),
                tier("WINS_15", "15 wins", 400_000, 3)));
        assertThat(l.options()).containsExactly(9, 10, 11, 12, 13, 14, 15);
        assertThat(l.rungFor(11).orElseThrow().priceMinor()).isEqualTo(120_000);
        assertThat(l.rungFor(13).orElseThrow().priceMinor()).isEqualTo(210_000);
    }

    @Test
    @DisplayName("an ordered tier not on the price list it was sold from: no ladder, delivered as before")
    void unknownOrderedTier() {
        assertThat(ladder("WINS_15", SEASON.subList(0, 6))).isNull();
    }

    @Test
    @DisplayName("paid for the service: every line but the payment fee and store credit, priced at the quote's time")
    void paidForService() {
        OrderEntity o = order("WINS_15", """
                {"issuedAt":"2026-10-08T02:54:00Z","lines":[
                  {"code":"BASE","amountMinor":400000},
                  {"code":"COUPON_DISCOUNT","amountMinor":-20000},
                  {"code":"WALLET_REDEMPTION","amountMinor":-1000},
                  {"code":"STORE_CREDIT","amountMinor":-10000},
                  {"code":"GATEWAY_FEE","amountMinor":9225}]}""");
        PaidForService paid = PaidForService.of(o, new ObjectMapper());
        assertThat(paid.servicePaidMinor()).isEqualTo(379_000);
        assertThat(paid.pricedAt()).isEqualTo(PRICED);

        OrderEntity payop = order("WINS_15", """
                {"issuedAt":1791428040.000000000,"lines":[
                  {"code":"BASE","amountMinor":400000},{"code":"PAYMENT_FEE","amountMinor":14200}]}""");
        PaidForService viaPayop = PaidForService.of(payop, new ObjectMapper());
        assertThat(viaPayop.servicePaidMinor()).as("a Payop method's fee is not the service either").isEqualTo(400_000);
        assertThat(viaPayop.pricedAt()).isEqualTo(Instant.ofEpochSecond(1_791_428_040L));
    }
}
