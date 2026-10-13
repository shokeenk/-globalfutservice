package com.globalfutservice.notify.email;

import com.globalfutservice.notify.OrderNotification;
import com.globalfutservice.notify.SettlementNotice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two Champions emails: the right figures, the choose-by date in India time and UTC, a
 * button to the result on the order page, and a clickable Live Chat Support link to the
 * order's own support page -- in the branded template the coin emails use.
 */
class ChampionsEmailsTest {

    private static final EmailTemplate.Brand BRAND = new EmailTemplate.Brand(
            "https://globalfutservices.com", "globalfutservices.com",
            "https://discord.gg/x", "Discord", "https://instagram.com/x", "Instagram");
    private static final String RESULT_URL = "https://globalfutservices.com/track?ref=GFS-26-592VBQ2Z#order-result";
    private static final String SUPPORT_URL = "https://globalfutservices.com/orders/GFS-26-592VBQ2Z/support";

    private static OrderNotification order(SettlementNotice s) {
        return new OrderNotification("GFS-26-592VBQ2Z", "DELIVERED", "Champs Boosting — 15 wins · Elite I",
                "₹3,895.00", "player@example.test", null, "COMFORT_TRADE", "BOOST_CHAMPS", "PlayStation",
                "https://globalfutservices.com/admin/orders/GFS-26-592VBQ2Z", null, null, null, s);
    }

    private static SettlementNotice owed() {
        return new SettlementNotice("CHAMPS_WINS", 15, 14, false, "₹475.00", null, "PENDING",
                Instant.parse("2026-10-18T05:00:00Z"), false, true);
    }

    @Test
    @DisplayName("the choose-by date in IST, with UTC beside it")
    void chooseBy() {
        assertThat(ChampionsEmails.chooseBy(Instant.parse("2026-10-18T05:00:00Z")))
                .isEqualTo("18 Oct 2026, 10:30 IST (05:00 UTC)");
        // Past midnight in India, still the day before in UTC: the UTC side carries its date.
        assertThat(ChampionsEmails.chooseBy(Instant.parse("2026-10-17T20:00:00Z")))
                .isEqualTo("18 Oct 2026, 01:30 IST (17 Oct, 20:00 UTC)");
    }

    @Test
    @DisplayName("delivered with wins short: ordered, achieved, the amount, the choose-by date, the button and live chat")
    void deliveredOwed() {
        TransactionalEmails.Rendered mail = ChampionsEmails.delivered(order(owed()), BRAND, RESULT_URL, SUPPORT_URL);

        assertThat(mail.subject()).isEqualTo("Order GFS-26-592VBQ2Z delivered — your Champions results");
        for (String body : new String[] {mail.html(), mail.text()}) {
            assertThat(body).contains("Wins Ordered").contains("15 Wins")
                    .contains("Wins Achieved").contains("14 Wins")
                    .contains("₹475.00")
                    .contains("18 Oct 2026, 10:30 IST (05:00 UTC)")
                    .contains("receive GFS Store Credit.");
        }
        // Escaped in the HTML, as every interpolated value is; plain in the text part.
        assertThat(mail.text()).contains("If you don't choose, you'll receive GFS Store Credit.");
        assertThat(mail.html()).contains("href=\"" + RESULT_URL + "\"").contains("VIEW YOUR CHAMPIONS RESULTS");
        assertThat(mail.html()).contains("<a href=\"" + SUPPORT_URL + "\"").contains(">Live Chat Support</a>");
        assertThat(mail.text()).contains("Live Chat Support: " + SUPPORT_URL);
        // Still the delivery notice it always was.
        assertThat(mail.html()).contains("receipt of this email closes the refund window");
        // The branded template, as the coin emails use.
        assertThat(mail.html()).contains("YOUR CHAMPIONS ORDER IS COMPLETE").contains("globalfutservices.com");
    }

    @Test
    @DisplayName("every win achieved: no amount, no date, no choice")
    void deliveredAllAchieved() {
        SettlementNotice all = new SettlementNotice("CHAMPS_WINS", 15, 15, false, null, null, "NONE", null, false, true);
        TransactionalEmails.Rendered mail = ChampionsEmails.delivered(order(all), BRAND, RESULT_URL, SUPPORT_URL);

        assertThat(mail.html()).contains("All wins achieved").doesNotContain("Settlement Amount")
                .doesNotContain("Choose on your order page").doesNotContain("₹475");
        assertThat(mail.html()).contains(">Live Chat Support</a>");
    }

    @Test
    @DisplayName("refund paid: the amount, Refund Paid, and live chat")
    void refundPaid() {
        SettlementNotice paid = new SettlementNotice("CHAMPS_WINS", 15, 14, false, "₹475.00", "REFUND", "PAID",
                null, false, true);
        TransactionalEmails.Rendered mail = ChampionsEmails.settled(order(paid), BRAND, RESULT_URL, SUPPORT_URL);

        assertThat(mail.subject()).isEqualTo("Your refund of ₹475.00 has been sent — order GFS-26-592VBQ2Z");
        assertThat(mail.html()).contains("Refund Amount").contains("₹475.00").contains("Refund Paid")
                .contains("<a href=\"" + SUPPORT_URL + "\"");
        assertThat(mail.text()).contains("Refund Amount: ₹475.00").contains("Status: Refund Paid");
    }

    @Test
    @DisplayName("store credit issued automatically: says so, and how it is used")
    void creditIssuedAutomatically() {
        SettlementNotice issued = new SettlementNotice("CHAMPS_WINS", 15, 14, false, "₹475.00", "STORE_CREDIT",
                "ISSUED", null, true, true);
        TransactionalEmails.Rendered mail = ChampionsEmails.settled(order(issued), BRAND, RESULT_URL, SUPPORT_URL);

        assertThat(mail.subject()).isEqualTo("₹475.00 GFS Store Credit added — order GFS-26-592VBQ2Z");
        assertThat(mail.html()).contains("Store Credit Amount").contains("Store Credit Issued")
                .contains("issued automatically as GFS Store Credit").contains("It does not expire")
                .contains(">Live Chat Support</a>");
    }
}
