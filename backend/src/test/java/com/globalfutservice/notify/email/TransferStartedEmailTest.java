package com.globalfutservice.notify.email;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import com.globalfutservice.notify.OrderNotification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** "Your coin transfer has started": what it says, where it links, and what it never carries. */
class TransferStartedEmailTest {

    private static final String REF = "GFS-26-TRACK001";
    private static final String TRACK = "https://globalfutservices.com/track?ref=" + REF;
    private static final String SUPPORT = "https://globalfutservices.com/orders/" + REF + "/support";
    private static final String DM = "https://discord.com/users/1300551868174569595";
    static final OrderNotification ORDER = new OrderNotification(REF, "IN_PROGRESS", "Buy Coins — 500K (PlayStation)",
            "₹8,250.00", "player@example.test", null, "PLAYER_AUCTION", "TRADING_SERVICE", "PlayStation",
            "https://globalfutservices.com/admin/orders/" + REF, null, null, null);
    static final EmailTemplate.Brand BRAND = new EmailTemplate.Brand("https://globalfutservices.com",
            "globalfutservices.com", "https://discord.com/invite/x", "Join the GFS Discord",
            "https://instagram.com/globalfutservices", "@globalfutservices");

    /** The partner's own vocabulary, as CustomerVocabularyTest forbids it. */
    private static final List<Pattern> PARTNER = List.of(Pattern.compile("(?i)snipe"),
            Pattern.compile("(?i)\\bcycle\\b"), Pattern.compile("(?i)\\blegacy\\b"), Pattern.compile("(?i)\\blimited\\b"),
            Pattern.compile("(?i)fut\\s*transfer|eatransfer"));

    private static TransactionalEmails.Rendered render() {
        return TransactionalEmails.transferStarted(ORDER, BRAND, TRACK, SUPPORT, DM);
    }

    @Test
    @DisplayName("the reference, \"Your coin transfer has started\", the tracking button and the plain URL")
    void says() {
        TransactionalEmails.Rendered r = render();
        assertThat(r.subject()).isEqualTo("Your coin transfer has started — order " + REF);
        assertThat(r.html()).contains(REF).contains("Your coin transfer has started").contains("TRACK YOUR ORDER")
                .contains("href=\"" + TRACK + "\"");
        // The address itself, readable, for a mail client that hides buttons.
        assertThat(r.html()).contains(">" + TRACK + "</a>");
        assertThat(r.text()).contains("Order #" + REF).contains("Status: Your coin transfer has started")
                .contains("Track your order: " + TRACK);
    }

    @Test
    @DisplayName("where to get help: the order's support page (live chat) and globalfutservices on Discord")
    void help() {
        TransactionalEmails.Rendered r = render();
        assertThat(r.html()).contains("href=\"" + SUPPORT + "\"").contains("href=\"" + DM + "\"")
                .contains("globalfutservices on Discord");
        assertThat(r.text()).contains(SUPPORT).contains(DM).contains("globalfutservices");
    }

    @Test
    @DisplayName("none of the partner's words, and nothing sensitive in any link")
    void clean() {
        TransactionalEmails.Rendered r = render();
        for (Pattern p : PARTNER) {
            assertThat(p.matcher(r.html()).find()).as(p.pattern()).isFalse();
            assertThat(p.matcher(r.text()).find()).as(p.pattern()).isFalse();
            assertThat(p.matcher(r.subject()).find()).as(p.pattern()).isFalse();
        }
        // The links carry the order reference and nothing else: no email address, no token.
        var hrefs = Pattern.compile("href=\"([^\"]+)\"").matcher(r.html()).results().map(m -> m.group(1)).toList();
        assertThat(hrefs).allSatisfy(h -> assertThat(h).doesNotContain("player@").doesNotContain("%40")
                .doesNotContainIgnoringCase("password").doesNotContainIgnoringCase("token"));
    }

    @Test
    @DisplayName("renders the email to the scratchpad for review (GFS_RENDER_DIR)")
    void renderForReview() throws Exception {
        String dir = System.getenv("GFS_RENDER_DIR");
        if (dir == null || dir.isBlank()) {
            return;
        }
        TransactionalEmails.Rendered r = render();
        Files.writeString(Path.of(dir, "transfer-started.html"), r.html());
        Files.writeString(Path.of(dir, "transfer-started.txt"), "Subject: " + r.subject() + "\n\n" + r.text());
    }
}
