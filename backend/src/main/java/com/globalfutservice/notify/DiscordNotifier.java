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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operator alerts over Discord.
 *
 * <p>One-way, and a webhook rather than a bot: a URL to POST to, with no gateway
 * connection to hold open, no token to refresh and no permissions to grant. Discord is
 * also where this business already talks to its customers, so it is where an operator
 * already is.
 *
 * <p><b>What this channel is for that the others are not.</b> WhatsApp and Telegram stay
 * quiet on unpaid orders because most of them are abandoned, and that is right for a
 * phone buzzing in someone's pocket. A Discord channel is a log you scroll, so it carries
 * new orders too — and it carries the one alert nothing else can raise: a customer
 * reporting a payment made outside the gateway. There is no callback behind that event.
 * If nobody is told, nobody checks, and somebody who has already sent money waits.
 *
 * <p><b>The vault line.</b> This class can only send what a
 * {@link PaymentClaimNotification} or an {@link OrderNotification} carries, and neither
 * carries a customer's EA sign-in. That is not a convention to be careful about here; it
 * is why those types are records with fixed fields. A sign-in reaching Discord would be
 * plaintext, retained by a third party, and readable by everyone in the channel forever
 * -- which would make the encrypted vault it was copied out of decorative.
 */
@Component
public class DiscordNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(DiscordNotifier.class);

    /** Discord's own limits. A payload over these is rejected whole, so trim first. */
    private static final int FIELD_VALUE_LIMIT = 1024;
    private static final int CONTENT_LIMIT = 2000;

    // Left rail of the embed. Money that needs checking is amber, everything else reads
    // as information rather than an action.
    private static final int AMBER = 0xF59E0B;
    private static final int GREEN = 0x22C55E;
    private static final int SLATE = 0x64748B;

    private final AppProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;
    /** Null when no bot is configured; the webhook then carries everything. */
    private final OrderTicketService tickets;

    public DiscordNotifier(AppProperties props, ObjectMapper mapper,
                           @org.springframework.beans.factory.annotation.Autowired(required = false)
                           OrderTicketService tickets) {
        this.props = props;
        this.mapper = mapper;
        this.tickets = tickets;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public boolean isEnabled() {
        AppProperties.Notifications n = props.notifications();
        // The admin id is not part of this. Without it the alert still arrives and simply
        // does not ping anyone, which is worth having; without the webhook there is
        // nowhere to send anything at all.
        return n.discordEnabled()
                && n.discordWebhookUrl() != null && !n.discordWebhookUrl().isBlank();
    }

    @Override
    public String channelName() {
        return "discord";
    }

    /* ----------------------------------------------------------------- events --- */

    /**
     * The alert this channel exists for.
     *
     * <p>Amber, mentioning the operator, and leading with the reference and the account,
     * because the job it starts is "open that account and look for this number".
     */
    @Override
    public void paymentClaimed(PaymentClaimNotification n) {
        /*
         * The ticket is the primary destination and the webhook is the fallback, not the
         * other way round. A webhook can only ever reach one fixed channel; a ticket gives
         * the verification conversation somewhere to live attached to its order.
         *
         * If a ticket was opened, this channel has already said everything below and
         * repeating it in the alerts channel would train an operator to ignore one of the
         * two. So the webhook fires only when the ticket did not.
         */
        if (tickets != null && tickets.openTicket(n).isPresent()) {
            return;
        }

        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("Order", "`" + n.publicRef() + "`", true));
        fields.add(field("Amount", "**" + n.amountFormatted() + "**", true));
        fields.add(field("Service", n.serviceLabel(), false));
        fields.add(field("Paid via", n.method(), true));
        fields.add(field("Into", "`" + n.destination() + "`", true));

        // Its own row, uncrowded, and in a code span so it can be copied cleanly into a
        // banking search -- this is the string the whole message exists to deliver.
        fields.add(field("Reference to check", "`" + n.reference() + "`", false));

        fields.add(field("Customer", contactLine(n.customerEmail(), n.customerDiscord()), false));

        // Whether, never what. See PaymentClaimNotification.
        fields.add(field("Account details",
                n.credentialsHeld() ? "submitted ✓" : "not needed", true));

        send(mention() + " 💸 **Payment reported — needs checking**",
                embed("Verify before fulfilling", AMBER, fields, n.adminDeepLink(), n.submittedAt()),
                n.publicRef());
    }

    /**
     * New orders, which the phone channels deliberately skip.
     *
     * <p>Unmentioned on purpose. Most orders that reach this point are never paid for, and
     * a channel that pings on every one of them is a channel whose pings get turned off --
     * taking the payment alerts with them.
     */
    @Override
    public void orderPlaced(OrderNotification n) {
        send("🧾 New order",
                embed(n.publicRef(), SLATE, List.of(
                        field("Service", n.serviceLabel(), false),
                        field("Amount", "**" + n.amountFormatted() + "**", true),
                        field("Delivery", readableMethod(n.deliveryMethod()), true),
                        field("Customer", contactLine(n.customerEmail(), n.customerDiscord()), false)
                ), n.adminDeepLink(), null),
                n.publicRef());
    }

    @Override
    public void readyToFulfil(OrderNotification n) {
        send(mention() + " 💰 **Paid — ready to fulfil**",
                embed(n.publicRef(), GREEN, List.of(
                        field("Service", n.serviceLabel(), false),
                        field("Amount", "**" + n.amountFormatted() + "**", true),
                        field("Delivery", readableMethod(n.deliveryMethod()), true)
                ), n.adminDeepLink(), null),
                n.publicRef());
    }

    @Override
    public void credentialsNeeded(OrderNotification n) {
        send("🔐 Waiting on the customer's sign-in",
                embed(n.publicRef(), SLATE, List.of(
                        field("Service", n.serviceLabel(), false),
                        field("Customer", contactLine(n.customerEmail(), n.customerDiscord()), false)
                ), n.adminDeepLink(), null),
                n.publicRef());
    }

    @Override
    public void orderDelivered(OrderNotification n) {
        send("✅ Delivered",
                embed(n.publicRef(), GREEN, List.of(
                        field("Service", n.serviceLabel(), false),
                        field("Amount", "**" + n.amountFormatted() + "**", true)
                ), n.adminDeepLink(), null),
                n.publicRef());
    }

    /* ------------------------------------------------------------- composition --- */

    /**
     * {@code <@id>}, the only form Discord turns into a ping.
     *
     * <p>A bare id renders as a number and a username renders as text; neither notifies
     * anybody, and both look like they worked.
     */
    private String mention() {
        String id = props.notifications().discordAdminId();
        if (id == null || id.isBlank()) {
            return "";
        }
        return "<@" + id.trim() + "> ";
    }

    private static Map<String, Object> field(String name, String value, boolean inline) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("value", clamp(value == null || value.isBlank() ? "—" : value, FIELD_VALUE_LIMIT));
        f.put("inline", inline);
        return f;
    }

    private static Map<String, Object> embed(String title, int colour,
                                             List<Map<String, Object>> fields,
                                             String adminLink, java.time.Instant timestamp) {
        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", clamp(title, 256));
        embed.put("color", colour);
        embed.put("fields", fields);
        if (adminLink != null && !adminLink.isBlank()) {
            embed.put("url", adminLink);
            embed.put("description", "[Open in admin console](" + adminLink + ")");
        }
        // Discord renders this in each viewer's own timezone, which beats stamping ours
        // into the text and making everyone else do the arithmetic.
        embed.put("timestamp", (timestamp == null ? java.time.Instant.now() : timestamp).toString());
        return embed;
    }

    private static String contactLine(String email, String discord) {
        if (email == null && discord == null) return "—";
        if (discord == null || discord.isBlank()) return email;
        if (email == null || email.isBlank()) return discord;
        return email + " · Discord: " + discord;
    }

    private static String readableMethod(String method) {
        if (method == null) return "—";
        return "COMFORT_TRADE".equals(method) ? "Comfort trade" : "Transfer market";
    }

    private static String clamp(String value, int limit) {
        if (value == null) return "—";
        return value.length() <= limit ? value : value.substring(0, limit - 1) + "…";
    }

    /* -------------------------------------------------------------- transport --- */

    private void send(String content, Map<String, Object> embed, String publicRef) {
        if (!isEnabled()) {
            log.debug("Discord disabled; would have posted about {}", publicRef);
            return;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("content", clamp(content, CONTENT_LIMIT));
            body.put("embeds", List.of(embed));
            /*
             * Only the configured operator can be pinged by this integration. Order data
             * is free text -- a customer's Discord handle, a service label, a reference
             * they typed -- and without this an "@everyone" pasted into any of those
             * fields would notify a whole server. The allow-list makes the mention above
             * the only one that can fire, whatever the text says.
             */
            body.put("allowed_mentions", mentionAllowList());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(props.notifications().discordWebhookUrl()))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                // Discord's body names the cause ("Unknown Webhook" for a deleted one,
                // rate-limit details otherwise). The URL is never logged: it is the
                // credential.
                log.warn("Discord notification for {} rejected: HTTP {} — {}",
                        publicRef, response.statusCode(), clamp(response.body(), 200));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Discord notification interrupted for {}", publicRef);
        } catch (Exception e) {
            /*
             * Swallowed, like every other notifier. An order that has been placed -- or
             * money that has been sent -- must never be undone because a webhook was
             * unreachable. NotificationService also runs this off the request thread, so
             * a slow Discord costs alert latency and nothing else.
             */
            log.warn("Discord notification failed for {}: {}", publicRef, e.getMessage());
        }
    }

    private Map<String, Object> mentionAllowList() {
        String id = props.notifications().discordAdminId();
        Map<String, Object> allowed = new LinkedHashMap<>();
        allowed.put("parse", List.of());
        allowed.put("users", (id == null || id.isBlank()) ? List.of() : List.of(id.trim()));
        return allowed;
    }
}
