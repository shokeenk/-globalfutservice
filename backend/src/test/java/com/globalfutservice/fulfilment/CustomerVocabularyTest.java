package com.globalfutservice.fulfilment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.orders.CustomerAction;
import com.globalfutservice.notify.CustomerActionNotification;
import com.globalfutservice.notify.DiscordBotClient;
import com.globalfutservice.notify.DiscordNotifier;
import com.globalfutservice.notify.EmailNotifier;
import com.globalfutservice.notify.OrderNotification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Customers see "GFS Transfer Method 3.0" and plain words. They never see the fulfilment
 * partner's name for a method, its settings or its status codes.
 *
 * <p>Scanned: every sentence {@link CustomerText} can produce, the storefront's three
 * translations, the customer emails and the order ticket's texts, and the email and ticket
 * message a held order actually sends. Comments are not scanned -- only what is shown.
 *
 * <p>Not scanned: the privacy policy, which names the partner on purpose, because the law
 * requires saying who receives a customer's sign-in.
 */
class CustomerVocabularyTest {

    private static final Path BACKEND = Path.of("src", "main", "java", "com", "globalfutservice");
    private static final Path FRONTEND = Path.of("..", "frontend", "src");

    /** The partner's words. Case-insensitive unless the pattern says otherwise. */
    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("(?i)snipe"),                          // Surgical Snipe, targetedSnipe, snipeLimited
            Pattern.compile("(?i)\\bcycle\\b"),                    // the "cycle" method
            Pattern.compile("(?i)\\blegacy\\b"),
            Pattern.compile("(?i)\\blimited\\b"),
            Pattern.compile("(?i)fut\\s*transfer|eatransfer"),     // the partner and its domains
            Pattern.compile("riskLevel|transferMethod|senderGroup|autoFinishCycle|topUpEnabled|minTransferAmount"
                    + "|stopOrderAfterOnboarding|skipCustomerCheck|lockOnboarding"));

    @Test
    @DisplayName("every sentence CustomerText can produce is in our words")
    void customerText() {
        assertClean("CustomerText", customerTextSentences());
        assertThat(CustomerText.forState(VendorStatusMap.State.SUBMITTED, CustomerAction.NONE))
                .contains("GFS Transfer Method 3.0");
    }

    @Test
    @DisplayName("the storefront's translations name the one method, and nothing of the partner's")
    void translations() throws Exception {
        for (String lang : List.of("en", "es", "fr")) {
            String source = Files.readString(FRONTEND.resolve("i18n").resolve(lang + ".ts"));
            List<String> strings = literals(source);
            assertClean(lang + ".ts", strings);
            assertThat(strings).as(lang + ".ts: deliveryTrading and deliveryFixed")
                    .filteredOn("GFS Transfer Method 3.0"::equals).hasSize(2);
            assertThat(String.join("\n", strings)).as(lang + ".ts").doesNotContain("Trading Method");
        }
    }

    @Test
    @DisplayName("customer emails and the order ticket's texts use none of the partner's words")
    void templates() throws Exception {
        for (Path file : templateFiles()) {
            assertClean(file.getFileName().toString(), literals(Files.readString(file)));
        }
    }

    private static List<String> customerTextSentences() {
        List<String> sentences = new ArrayList<>();
        for (VendorStatusMap.State state : VendorStatusMap.State.values()) {
            for (CustomerAction action : CustomerAction.values()) {
                String s = CustomerText.forState(state, action);
                if (s != null) sentences.add(s);
            }
        }
        for (CustomerAction action : CustomerAction.values()) {
            sentences.add(CustomerText.forAction(action));
        }
        return sentences;
    }

    private static List<Path> templateFiles() throws Exception {
        List<Path> files = new ArrayList<>();
        try (var emails = Files.list(BACKEND.resolve("notify").resolve("email"))) {
            emails.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
        }
        files.add(BACKEND.resolve("notify").resolve("EmailNotifier.java"));
        files.add(BACKEND.resolve("notify").resolve("OrderTicketService.java"));
        files.add(BACKEND.resolve("fulfilment").resolve("CustomerText.java"));
        files.add(BACKEND.resolve("orders").resolve("web").resolve("OrderMapper.java"));
        assertThat(files).hasSizeGreaterThan(5);
        return files;
    }

    @Test
    @DisplayName("the partner's documented status codes never appear in anything a customer reads")
    void vendorCodes() throws Exception {
        List<String> codes = new ArrayList<>();
        for (String field : List.of("Status", "AccountCheck", "EconomyState")) {
            for (String v : documented(field)) {
                // Codes like "finished" are ordinary words; the ones that are not are the tell.
                if (v.matches(".*[a-z][A-Z].*")) codes.add(v);
            }
        }
        assertThat(codes).contains("wrongBA", "wrongUserPass");

        // Everything the other checks read: sentences, sent messages, translations, templates.
        List<String> shown = new ArrayList<>(sentOnHold());
        shown.addAll(customerTextSentences());
        for (String lang : List.of("en", "es", "fr")) {
            shown.addAll(literals(Files.readString(FRONTEND.resolve("i18n").resolve(lang + ".ts"))));
        }
        for (Path file : templateFiles()) {
            shown.addAll(literals(Files.readString(file)));
        }
        String all = String.join("\n", shown);
        for (String code : codes) {
            assertThat(Pattern.compile("(?<![A-Za-z])" + Pattern.quote(code) + "(?![A-Za-z])").matcher(all).find())
                    .as("vendor code " + code).isFalse();
        }
    }

    @Test
    @DisplayName("what a held order actually sends -- the email and the ticket message -- is clean for every action")
    void sentMessages() {
        assertClean("held-order messages", sentOnHold());
    }

    /** The email and the ticket message for every action, as the customer receives them. */
    private static List<String> sentOnHold() {
        AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(props.publicUrl()).thenReturn("https://globalfutservices.com");
        when(props.notifications().emailEnabled()).thenReturn(true);
        when(props.notifications().emailFrom()).thenReturn("orders@globalfutservices.com");
        List<String> out = new ArrayList<>();
        for (CustomerAction action : CustomerAction.values()) {
            CustomerActionNotification n = new CustomerActionNotification(new OrderNotification(
                    "GFS-26-VOCAB001", "ON_HOLD", "Buy Coins — 500K (PC)", "₹8,250.00", "p@example.test", null,
                    "PLAYER_AUCTION", "TRADING_SERVICE", "PC", null, null, null, null), CustomerText.forAction(action));

            JavaMailSender sender = mock(JavaMailSender.class);
            new EmailNotifier(props, sender).customerActionNeeded(n);
            ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
            verify(sender).send(mail.capture());
            out.add(mail.getValue().getSubject());
            out.add(mail.getValue().getText());

            DiscordBotClient bot = mock(DiscordBotClient.class);
            when(bot.isEnabled()).thenReturn(true);
            when(bot.findTicketChannel("GFS-26-VOCAB001")).thenReturn(Optional.of("ticket"));
            new DiscordNotifier(props, new ObjectMapper(), null, bot).customerActionNeeded(n);
            ArgumentCaptor<String> post = ArgumentCaptor.forClass(String.class);
            verify(bot).postMessage(eq("ticket"), post.capture());
            out.add(post.getValue());
        }
        return out;
    }

    private static void assertClean(String where, List<String> texts) {
        Map<String, String> hits = new LinkedHashMap<>();
        for (String text : texts) {
            for (Pattern p : FORBIDDEN) {
                Matcher m = p.matcher(text);
                if (m.find()) hits.put(text.length() > 120 ? text.substring(0, 120) + "..." : text, m.group());
            }
        }
        assertThat(hits).as(where + ": text a customer reads, with the partner's word found in it").isEmpty();
    }

    /** The "Possible Values" column of the Query Order Status documentation, for one field. */
    private static List<String> documented(String field) throws Exception {
        String collection = Files.readString(Path.of("..", "docs", "vendor", "fut-transfer.postman_collection.json"));
        Matcher m = Pattern.compile("\\| " + field + " \\| [^|]+\\| ([^|]+) \\|").matcher(collection);
        assertThat(m.find()).as(field + " is documented in the collection").isTrue();
        List<String> values = new ArrayList<>();
        for (String v : m.group(1).split(",")) {
            if (!v.isBlank()) values.add(v.trim());
        }
        return values;
    }

    /**
     * The string literals in a Java or TypeScript source, comments skipped: '...', "...",
     * `...` and Java text blocks.
     */
    static List<String> literals(String src) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                int eol = src.indexOf('\n', i);
                i = eol < 0 ? n : eol + 1;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (src.startsWith("\"\"\"", i)) {
                int end = src.indexOf("\"\"\"", i + 3);
                out.add(src.substring(i + 3, end));
                i = end + 3;
            } else if (c == '"' || c == '\'' || c == '`') {
                StringBuilder s = new StringBuilder();
                int j = i + 1;
                while (j < n && src.charAt(j) != c) {
                    if (src.charAt(j) == '\\' && j + 1 < n) j++;
                    s.append(src.charAt(j));
                    j++;
                }
                out.add(s.toString());
                i = j + 1;
            } else {
                i++;
            }
        }
        return out;
    }

    @Test
    @DisplayName("the scanner reads strings and skips comments")
    void scanner() {
        assertThat(literals("""
                // a futtransfer comment
                /* targetedSnipe */ const a = 'it\\'s fine'; const b = "https://x.test/y"; const c = `t ${1}`;
                String d = \"\"\"
                    block\"\"\";
                """)).containsExactly("it's fine", "https://x.test/y", "t ${1}", "\n    block");
    }
}
