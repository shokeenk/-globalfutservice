package com.globalfutservice.admin;

import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Orders page's filters, and the CSV its export writes. */
class AdminOrderFilterTest {

    private static AdminOrderFilter parse(String service, String status, String platform,
                                          LocalDate from, LocalDate to, String search) {
        return AdminOrderFilter.parse(service, status, platform, from, to, search, false,
                AdminOrderQueries.BUSINESS_ZONE);
    }

    @Test
    @DisplayName("the Boosting tab covers both boosting services; the dropdown can pick one")
    void serviceGroups() {
        assertThat(parse("BOOSTING", null, null, null, null, null).skus())
                .containsExactlyInAnyOrder(Sku.BOOST_CHAMPS, Sku.BOOST_RIVALS);
        assertThat(parse("rivals", null, null, null, null, null).skus()).containsExactly(Sku.BOOST_RIVALS);
        assertThat(parse("COINS", null, null, null, null, null).skus()).containsExactly(Sku.TRADING_SERVICE);
        assertThat(parse("COACHING", null, null, null, null, null).skus()).containsExactly(Sku.COACHING);
        assertThat(parse(null, null, null, null, null, null).skus()).isEmpty();
    }

    @Test
    @DisplayName("a tab's statuses arrive as a list of real statuses")
    void statusList() {
        assertThat(parse(null, "DELIVERED,COMPLETED", null, null, null, null).statuses())
                .containsExactlyInAnyOrder(OrderStatus.DELIVERED, OrderStatus.COMPLETED);
        assertThat(parse(null, "ALL", null, null, null, null).statuses()).isEmpty();
        assertThat(parse(null, "on_hold", "xbox", null, null, null))
                .satisfies(f -> {
                    assertThat(f.statuses()).containsExactly(OrderStatus.ON_HOLD);
                    assertThat(f.platform()).isEqualTo(Platform.XBOX);
                });
    }

    @Test
    @DisplayName("unknown values are refused rather than ignored")
    void unknownValues() {
        assertThatThrownBy(() -> parse("REWARDS", null, null, null, null, null))
                .isInstanceOf(ApiExceptions.BadRequestException.class);
        assertThatThrownBy(() -> parse(null, "READY,NEW", null, null, null, null))
                .isInstanceOf(ApiExceptions.BadRequestException.class);
        assertThatThrownBy(() -> parse(null, null, "SWITCH", null, null, null))
                .isInstanceOf(ApiExceptions.BadRequestException.class);
    }

    @Test
    @DisplayName("dates are whole days in India, and the last day is included")
    void datesInIndia() {
        AdminOrderFilter f = parse(null, null, null,
                LocalDate.parse("2026-09-26"), LocalDate.parse("2026-09-27"), null);

        assertThat(f.createdFrom()).isEqualTo(Instant.parse("2026-09-25T18:30:00Z"));
        // Midnight at the end of the 27th, India time.
        assertThat(f.createdBefore()).isEqualTo(Instant.parse("2026-09-27T18:30:00Z"));
    }

    @Test
    @DisplayName("a range that ends before it starts is refused")
    void backwardsRange() {
        assertThatThrownBy(() -> parse(null, null, null,
                LocalDate.parse("2026-09-27"), LocalDate.parse("2026-09-01"), null))
                .isInstanceOf(ApiExceptions.BadRequestException.class)
                .hasMessageContaining("ends before it starts");
    }

    @Test
    @DisplayName("search is trimmed, blank means none, and a very long one is refused")
    void searchText() {
        assertThat(parse(null, null, null, null, null, "  rahul  ").search()).isEqualTo("rahul");
        assertThat(parse(null, null, null, null, null, "   ").search()).isNull();
        assertThatThrownBy(() -> parse(null, null, null, null, null, "x".repeat(101)))
                .isInstanceOf(ApiExceptions.BadRequestException.class);
    }

    @Test
    @DisplayName("a typed % or _ is searched for, not treated as a wildcard")
    void likeEscaping() {
        assertThat(AdminOrderSpecs.escapeLike("50%_off\\")).isEqualTo("50\\%\\_off\\\\");
    }

    @Test
    @DisplayName("CSV: customer-typed formulas are neutralised, awkward text is quoted, numbers stay numbers")
    void csvCells() {
        assertThat(Csv.cell("=HYPERLINK(\"http://x\")")).isEqualTo("\"'=HYPERLINK(\"\"http://x\"\")\"");
        assertThat(Csv.cell("+91 98765")).isEqualTo("'+91 98765");
        assertThat(Csv.cell("@rahul")).isEqualTo("'@rahul");
        assertThat(Csv.cell("-5")).isEqualTo("'-5");
        assertThat(Csv.cell(new java.math.BigDecimal("-5.00"))).isEqualTo("-5.00");
        assertThat(Csv.cell("Buy Coins — 500K, PS")).isEqualTo("\"Buy Coins — 500K, PS\"");
        assertThat(Csv.cell(null)).isEmpty();
    }

    @Test
    @DisplayName("CSV: starts with a byte-order mark so Excel reads the ₹ correctly")
    void csvFile() {
        byte[] bytes = new Csv().row(List.of("Total", "₹")).bytes();
        assertThat(Arrays.copyOfRange(bytes, 0, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        assertThat(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8)).isEqualTo("Total,₹\r\n");
    }
}
