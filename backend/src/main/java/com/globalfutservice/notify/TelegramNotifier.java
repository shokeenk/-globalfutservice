package com.globalfutservice.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Operator alerts over Telegram.
 *
 * <p><b>Why this exists alongside {@link WhatsAppNotifier}.</b> WhatsApp is where this
 * audience already lives, so it is the right long-term channel — but the Cloud API is
 * gated behind Meta business verification, a WhatsApp Business Account, a dedicated
 * sender number, and a message template that a human at Meta has to approve. That is
 * days of paperwork before the first alert lands.
 *
 * <p>Telegram is a bot token and a chat id. It is free, it has no per-message cost, no
 * template approval, no 24-hour session window, and it pushes to a phone exactly like
 * WhatsApp does. For an internal alert to the owner's own device, none of what WhatsApp
 * charges for is being used.
 *
 * <p>Both channels are registered at once and both are independently switchable, because
 * they are not really alternatives: run Telegram today, finish the Meta onboarding at
 * leisure, and turn WhatsApp on when the template clears. Nothing else has to change.
 *
 * <p>Same discipline as every other notifier: the message is a <b>pointer</b> — a
 * reference, an amount, and a deep link into the admin console. Never the customer's
 * sign-in, and never anything from the vault.
 */
@Component
public class TelegramNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    private final AppProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public TelegramNotifier(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public boolean isEnabled() {
        AppProperties.Notifications n = props.notifications();
        return n.telegramEnabled()
                && n.telegramBotToken() != null && !n.telegramBotToken().isBlank()
                && n.telegramChatId() != null && !n.telegramChatId().isBlank();
    }

    @Override
    public String channelName() {
        return "telegram";
    }

    /**
     * Silent, for the same reason WhatsApp is: this fires before the customer has
     * paid, and most orders that reach it are abandoned.
     */
    @Override
    public void orderPlaced(OrderNotification n) {
        log.debug("Telegram intentionally silent for unpaid order {}", n.publicRef());
    }

    @Override
    public void readyToFulfil(OrderNotification n) {
        send("💰 PAID — ready to fulfil", n);
    }

    @Override
    public void credentialsNeeded(OrderNotification n) {
        send("🔐 Awaiting customer sign-in", n);
    }

    @Override
    public void orderDelivered(OrderNotification n) {
        send("✅ Delivered", n);
    }

    private void send(String headline, OrderNotification n) {
        if (!isEnabled()) {
            log.debug("Telegram disabled; would have sent '{}' for {}", headline, n.publicRef());
            return;
        }
        AppProperties.Notifications cfg = props.notifications();
        try {
            /*
             * HTML rather than Markdown. Telegram's legacy Markdown parser throws on
             * an unbalanced underscore or asterisk, and service labels are free text
             * from the rate card — "Champs 15 wins (PS5)" is one stray character away
             * from a 400. HTML only needs three entities escaped, which is bounded.
             */
            String text = """
                    <b>%s</b>
                    Ref: <code>%s</code>
                    %s
                    Amount: <b>%s</b>
                    Method: %s

                    <a href="%s">Open in admin console</a>"""
                    .formatted(
                            escape(headline),
                            escape(n.publicRef()),
                            escape(n.serviceLabel()),
                            escape(n.amountFormatted()),
                            escape(readableMethod(n.deliveryMethod())),
                            n.adminDeepLink());

            Map<String, Object> body = Map.of(
                    "chat_id", cfg.telegramChatId(),
                    "text", text,
                    "parse_mode", "HTML",
                    // The deep link is the point of the message; a preview card of the
                    // admin login screen underneath it is noise.
                    "disable_web_page_preview", true);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.telegramApiBaseUrl() + "/bot"
                            + cfg.telegramBotToken() + "/sendMessage"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                // The body carries Telegram's own reason ("chat not found", "bot was
                // blocked"), which is the difference between a five-minute fix and an
                // afternoon. It contains no customer data.
                log.warn("Telegram notification for {} rejected: HTTP {} — {}",
                        n.publicRef(), response.statusCode(), truncate(response.body()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Telegram notification interrupted for {}", n.publicRef());
        } catch (Exception e) {
            // Swallowed on purpose. A paid order must never roll back because a
            // notification failed.
            log.warn("Telegram notification failed for {}: {}", n.publicRef(), e.getMessage());
        }
    }

    private static String readableMethod(String method) {
        if (method == null) return "-";
        return "COMFORT_TRADE".equals(method) ? "Comfort trade" : "Transfer market";
    }

    /** The three characters Telegram's HTML mode treats as markup. */
    private static String escape(String value) {
        if (value == null) return "-";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String truncate(String body) {
        if (body == null) return "";
        return body.length() <= 200 ? body : body.substring(0, 200) + "…";
    }
}
