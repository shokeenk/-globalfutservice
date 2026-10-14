package com.globalfutservice.notify.email;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import com.globalfutservice.notify.OrderNotification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Your coins are on the way": the order, the amount, the Track order button to the partner's
 * page exactly as stored, and the note that the link is the customer's alone.
 */
class CoinsOnTheWayEmailTest {

    private static final String REF = "GFS-26-TRACK001";
    /** With branding and language parameters, which must reach the customer untouched. */
    private static final String LINK = "https://transferstatus.top/abc12345?shop=gfs&lang=de";
    private static final String SUPPORT = "https://globalfutservices.com/orders/" + REF + "/support";
    private static final OrderNotification ORDER = TransferStartedEmailTest.ORDER;

    private static final List<Pattern> PARTNER = List.of(Pattern.compile("(?i)snipe"),
            Pattern.compile("(?i)\\bcycle\\b"), Pattern.compile("(?i)\\blegacy\\b"), Pattern.compile("(?i)\\blimited\\b"),
            Pattern.compile("(?i)fut\\s*transfer|eatransfer"));

    private static TransactionalEmails.Rendered render() {
        return TransactionalEmails.coinsOnTheWay(ORDER, TransferStartedEmailTest.BRAND, LINK, SUPPORT);
    }

    @Test
    @DisplayName("subject, order number, amount, the Track order button to the link as stored, and the note")
    void says() {
        TransactionalEmails.Rendered r = render();
        assertThat(r.subject()).isEqualTo("Your coins are on the way — order " + REF);
        assertThat(r.html()).contains(REF).contains("₹8,250.00").contains("TRACK ORDER")
                // HTML-escaped in the attribute, which a mail client reads back as the link itself.
                .contains("href=\"https://transferstatus.top/abc12345?shop=gfs&amp;lang=de\"")
                .contains("Don&#39;t share this link");
        assertThat(r.text()).contains("Order #" + REF).contains("Amount: ₹8,250.00")
                .contains("Track order: " + LINK)
                .contains(TransactionalEmails.TRACKING_LINK_NOTE);
        assertThat(TransactionalEmails.TRACKING_LINK_NOTE).isEqualTo("Live delivery status from our transfer partner. "
                + "If your EA login details need fixing, you can update them here. Don't share this link.");
    }

    @Test
    @DisplayName("none of the partner's words; the partner's link is the only address outside our own")
    void clean() {
        TransactionalEmails.Rendered r = render();
        for (Pattern p : PARTNER) {
            assertThat(p.matcher(r.html()).find()).as(p.pattern()).isFalse();
            assertThat(p.matcher(r.text()).find()).as(p.pattern()).isFalse();
            assertThat(p.matcher(r.subject()).find()).as(p.pattern()).isFalse();
        }
        var hrefs = Pattern.compile("href=\"([^\"]+)\"").matcher(r.html()).results().map(m -> m.group(1)).toList();
        assertThat(hrefs).filteredOn(h -> h.contains("transferstatus")).hasSize(1);
        assertThat(hrefs).allSatisfy(h -> assertThat(h).doesNotContain("player@").doesNotContain("%40"));
    }

    @Test
    @DisplayName("renders the email to the scratchpad for review (GFS_RENDER_DIR)")
    void renderForReview() throws Exception {
        String dir = System.getenv("GFS_RENDER_DIR");
        if (dir == null || dir.isBlank()) {
            return;
        }
        TransactionalEmails.Rendered r = render();
        Files.writeString(Path.of(dir, "coins-on-the-way.html"), r.html());
        Files.writeString(Path.of(dir, "coins-on-the-way.txt"), "Subject: " + r.subject() + "\n\n" + r.text());
    }
}
