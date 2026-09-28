package com.globalfutservice.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A new listing's code is made from its name, in the same shape as the existing ones. */
class ListingCodeTest {

    @Test
    @DisplayName("letters and digits, joined by underscores, 40 at most")
    void code() {
        assertThat(ListingService.code("16 wins · Elite I+")).isEqualTo("16_WINS_ELITE_I");
        assertThat(ListingService.code("  Division 1 to Elite  ")).isEqualTo("DIVISION_1_TO_ELITE");
        assertThat(ListingService.code("!!!")).isEmpty();
        assertThat(ListingService.code("a".repeat(50))).hasSize(40);
    }
}
