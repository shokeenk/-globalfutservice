package com.globalfutservice.fulfilment;

import com.globalfutservice.fulfilment.PartnerLinkVisibility.Style;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which address customers are given for FUT Transfer's tracking link: the status page FUT
 * Transfer said to use while its short links answer 404, or the short link as returned.
 */
class PartnerLinkVisibilityTest {

    private static final String SHORT = "https://transferstatus.top/abc12345";

    private static VendorOrderLedger.PartnerLink link(String code, String shop, String special) {
        return new VendorOrderLedger.PartnerLink(SHORT, code, shop, special);
    }

    @Test
    @DisplayName("STATUS_PAGE: futtransfer.top/orderStatus?code=<code>, with &shop=<shop> when the shop is not empty")
    void statusPage() {
        PartnerLinkVisibility v = new PartnerLinkVisibility(true, Style.STATUS_PAGE);
        assertThat(v.customerUrl(link("abc12345", null, null)))
                .isEqualTo("https://futtransfer.top/orderStatus?code=abc12345");
        assertThat(v.customerUrl(link("abc12345", "", null)))
                .isEqualTo("https://futtransfer.top/orderStatus?code=abc12345");
        assertThat(v.customerUrl(link("abc12345", "gfs", null)))
                .isEqualTo("https://futtransfer.top/orderStatus?code=abc12345&shop=gfs");
        // The shop goes as a parameter, safely encoded: never a way to change the address.
        assertThat(v.customerUrl(link("abc12345", "a&b=c", null)))
                .isEqualTo("https://futtransfer.top/orderStatus?code=abc12345&shop=a%26b%3Dc");
        assertThat(v.shortLinkBecause(link("abc12345", "gfs", null))).isNull();
    }

    @Test
    @DisplayName("STATUS_PAGE, but the link came with specialParams or without a code: the short link, and why")
    void statusPageFallsBack() {
        PartnerLinkVisibility v = new PartnerLinkVisibility(true, Style.STATUS_PAGE);
        assertThat(v.customerUrl(link("abc12345", "gfs", "lang=de"))).isEqualTo(SHORT);
        assertThat(v.shortLinkBecause(link("abc12345", "gfs", "lang=de"))).contains("specialParams");
        assertThat(v.customerUrl(link(null, null, null))).isEqualTo(SHORT);
        assertThat(v.shortLinkBecause(link(null, null, null))).contains("no short code");
    }

    @Test
    @DisplayName("SHORT_LINK: the link exactly as FUT Transfer returned it")
    void shortLink() {
        PartnerLinkVisibility v = new PartnerLinkVisibility(true, Style.SHORT_LINK);
        assertThat(v.customerUrl(link("abc12345", "gfs", null))).isEqualTo(SHORT);
        assertThat(v.shortLinkBecause(link("abc12345", "gfs", null))).isNull();
    }

    @Test
    @DisplayName("hidden from customers: none given, though staff still see the address the style makes")
    void hidden() {
        PartnerLinkVisibility v = new PartnerLinkVisibility(false, Style.STATUS_PAGE);
        assertThat(v.customerUrl(link("abc12345", null, null))).isNull();
        assertThat(v.address(link("abc12345", null, null))).isEqualTo("https://futtransfer.top/orderStatus?code=abc12345");
        assertThat(v.customerUrl(null)).isNull();
    }

    @Test
    @DisplayName("the code of a link already stored is read off its address: the path, or a code parameter")
    void codeFromAddress() {
        assertThat(FutTransferClient.shortCodeOf("https://transferstatus.top/zz9911aa")).isEqualTo("zz9911aa");
        assertThat(FutTransferClient.shortCodeOf("https://transferstatus.top/abc12345?lang=de")).isEqualTo("abc12345");
        assertThat(FutTransferClient.shortCodeOf("https://futtransfer.top/orderStatus?code=abc12345&shop=x"))
                .isEqualTo("abc12345");
        assertThat(FutTransferClient.shortCodeOf("https://transferstatus.top/")).isNull();
        assertThat(FutTransferClient.shortCodeOf("https://transferstatus.top/a-b")).isNull();
        assertThat(FutTransferClient.shortCodeOf(null)).isNull();
    }
}
