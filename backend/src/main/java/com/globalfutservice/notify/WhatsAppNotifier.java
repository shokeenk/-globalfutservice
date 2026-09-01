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
import java.util.List;
import java.util.Map;

/**
 * Pushes new-order alerts to the operator's WhatsApp using the official Cloud API.
 *
 * <p>Two decisions worth defending:
 *
 * <p><b>The Cloud API, not an unofficial client library.</b> The libraries that drive a
 * personal WhatsApp account get numbers banned, and they break without warning — usually
 * at two in the morning, a week after handover, when nobody remembers how it was set up.
 * The Cloud API costs a Meta business verification and then keeps working.
 *
 * <p><b>The message is a pointer, not a payload.</b> It contains an order reference, an
 * amount and a link into the admin console. It never contains the customer's EA sign-in,
 * their email, or anything else that would be awkward to find in a phone backup.
 */
@Component
public class WhatsAppNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppNotifier.class);

    private final AppProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public WhatsAppNotifier(AppProperties props, ObjectMapper mapper) {
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
        return n.whatsappEnabled()
                && n.whatsappAccessToken() != null && !n.whatsappAccessToken().isBlank()
                && n.whatsappPhoneNumberId() != null && !n.whatsappPhoneNumberId().isBlank()
                && n.whatsappOperatorNumber() != null && !n.whatsappOperatorNumber().isBlank();
    }

    @Override
    public String channelName() {
        return "whatsapp";
    }

    /**
     * Deliberately silent.
     *
     * <p>`orderPlaced` fires at AWAITING_PAYMENT — the instant a customer clicks
     * pay, before anything is charged. Most of those never complete. Buzzing a
     * phone for every abandoned cart is how an operator learns to ignore the
     * channel, and an ignored alert is worse than no alert. Email still records
     * it; WhatsApp waits until there is money and work.
     */
    @Override
    public void orderPlaced(OrderNotification n) {
        log.debug("WhatsApp intentionally silent for unpaid order {}", n.publicRef());
    }

    /** Money settled and the order is in the queue. This is the alert that matters. */
    @Override
    public void readyToFulfil(OrderNotification n) {
        send(n, "PAID — ready to fulfil");
    }

    @Override
    public void orderDelivered(OrderNotification n) {
        send(n, "Delivered");
    }

    @Override
    public void credentialsNeeded(OrderNotification n) {
        send(n, "Awaiting sign-in");
    }

    private void send(OrderNotification n, String headline) {
        if (!isEnabled()) {
            log.debug("WhatsApp disabled; would have sent '{}' for {}", headline, n.publicRef());
            return;
        }
        AppProperties.Notifications cfg = props.notifications();
        try {
            // A template message with positional parameters. Free-form messages are only
            // deliverable inside a 24-hour customer-initiated window, which an operator
            // alert is definitionally outside of.
            Map<String, Object> body = Map.of(
                    "messaging_product", "whatsapp",
                    "to", cfg.whatsappOperatorNumber(),
                    "type", "template",
                    "template", Map.of(
                            "name", cfg.whatsappTemplateName(),
                            "language", Map.of("code", "en"),
                            "components", List.of(Map.of(
                                    "type", "body",
                                    "parameters", List.of(
                                            param(headline),
                                            param(n.publicRef()),
                                            param(n.serviceLabel()),
                                            param(n.amountFormatted()))))));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.whatsappApiBaseUrl() + "/"
                            + cfg.whatsappPhoneNumberId() + "/messages"))
                    .header("Authorization", "Bearer " + cfg.whatsappAccessToken())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("WhatsApp notification for {} rejected: HTTP {}",
                        n.publicRef(), response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("WhatsApp notification interrupted for {}", n.publicRef());
        } catch (Exception e) {
            // Swallowed on purpose. A paid order must never roll back because a
            // notification failed.
            log.warn("WhatsApp notification failed for {}: {}", n.publicRef(), e.getMessage());
        }
    }

    private static Map<String, Object> param(String text) {
        return Map.of("type", "text", "text", text == null ? "-" : text);
    }
}
