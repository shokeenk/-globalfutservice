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

    /**
     * The zone the coaching is actually delivered in, so the coach never does arithmetic.
     *
     * <p>Matches {@code OrderTicketService}'s: an operator reading a ticket and a coach
     * reading a booking are usually the same person, and two business zones would be one
     * too many.
     */
    private static final java.time.ZoneId BUSINESS_ZONE = java.time.ZoneId.of("Asia/Kolkata");

    private static final java.time.format.DateTimeFormatter SESSION_TIME =
            java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, HH:mm");

    private final AppProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;
    /** Null when no bot is configured; the webhook then carries everything. */
    private final OrderTicketService tickets;
    /**
     * Also null without a bot. Held directly, not through {@link OrderTicketService},
     * because coaching posts into channels that are not order tickets — the staff
     * calendar channel is nobody's ticket.
     */
    private final DiscordBotClient bot;

    public DiscordNotifier(AppProperties props, ObjectMapper mapper,
                           @org.springframework.beans.factory.annotation.Autowired(required = false)
                           OrderTicketService tickets,
                           @org.springframework.beans.factory.annotation.Autowired(required = false)
                           DiscordBotClient bot) {
        this.props = props;
        this.mapper = mapper;
        this.tickets = tickets;
        this.bot = bot;
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
        if (n.coachingDetails() != null) {
            fields.add(field("Coaching", n.coachingDetails(), false));
        }
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
     * The payment screenshot, a moment after the claim it belongs to.
     *
     * <p>Sent the way the claim was: into its ticket when the bot opened one, to the
     * webhook otherwise. It used to go to the bot and nowhere else, so an install running
     * on the webhook alone delivered the claim and never the image that proves it.
     */
    @Override
    public void paymentProofAttached(PaymentProofNotification n) {
        if (tickets != null && tickets.attachScreenshot(n.publicRef(), n.image(), n.contentType())) {
            return;
        }
        sendImage("📸 Payment screenshot for `" + n.publicRef() + "`", n.image(),
                OrderTicketService.filenameFor(n.publicRef(), n.contentType()), n.publicRef());
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
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("Service", n.serviceLabel(), false));
        if (n.coachingDetails() != null) {
            fields.add(field("Coaching", n.coachingDetails(), false));
        }
        fields.add(field("Amount", "**" + n.amountFormatted() + "**", true));
        fields.add(field("Delivery", readableMethod(n.deliveryMethod()), true));
        fields.add(field("Customer", contactLine(n.customerEmail(), n.customerDiscord()), false));
        send("🧾 New order", embed(n.publicRef(), SLATE, fields, n.adminDeepLink(), null), n.publicRef());
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

    /* --------------------------------------------------------------- coaching --- */

    @Override
    public void coachingBooked(CoachingBookingNotification n) {
        announceSession("Coaching session booked", GREEN, n,
                "A session has been booked.");
    }

    @Override
    public void coachingRescheduled(CoachingBookingNotification n) {
        announceSession("Coaching session moved", AMBER, n,
                "A session has been moved.");
    }

    @Override
    public void coachingCancelled(CoachingBookingNotification n) {
        announceSession("Coaching session cancelled", SLATE, n,
                "A session has been cancelled. The slot is free again.");
    }

    /**
     * Two audiences, one embed.
     *
     * <p>The staff channel is where whoever coaches keeps their calendar. The order's own
     * ticket is where the customer already is, so the same facts land in front of both
     * without either having to be told to look somewhere else.
     *
     * <p>Every step is independent and every step can be skipped. No coaching channel
     * configured, no bot, no ticket for this order — each is a reason to post less, never
     * a reason to fail. The booking is committed before any of this runs.
     */
    private void announceSession(String title, int colour,
                                 CoachingBookingNotification n, String lead) {
        if (bot == null || !bot.isEnabled()) {
            log.debug("No Discord bot; not announcing session {}", n.sessionRef());
            return;
        }

        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("Session", n.sessionLabel(), true));
        fields.add(field("Coach", n.coachName(), true));
        fields.add(field("Order", n.orderRef() == null ? "—" : "`" + n.orderRef() + "`", true));
        /*
         * Both zones, always. IST is the zone the coach works in and the only one they
         * should have to think in; the customer's zone is the one the customer will quote
         * back on the day. Printing one and leaving the other to be worked out is how a
         * session gets missed by exactly the offset between them.
         */
        fields.add(field("Starts (IST)", inZone(n.startsAt(), BUSINESS_ZONE), false));
        fields.add(field("Starts (customer)", inCustomerZone(n), false));
        if (n.previousStartsAt() != null) {
            fields.add(field("Moved from (IST)",
                    inZone(n.previousStartsAt(), BUSINESS_ZONE), false));
        }
        fields.add(field("Customer", contactLine(n.customerEmail(), n.customerName()), false));
        fields.add(field("Payment", n.paymentStatus(), true));

        Map<String, Object> embed = embed(title, colour, fields, null, n.startsAt());

        String coachingChannel = props.notifications().discordCoachingChannelId();
        if (coachingChannel != null && !coachingChannel.isBlank()) {
            postQuietly(coachingChannel, lead, embed, n.sessionRef(), "coaching channel");
        }

        // The customer's own ticket, when the order has one. Looked up rather than
        // carried on the notification, so the coaching service stays free of Discord.
        if (n.orderRef() == null) {
            return;
        }
        try {
            bot.findTicketChannel(n.orderRef()).ifPresent(ticketChannel ->
                    postQuietly(ticketChannel, lead, embed, n.sessionRef(), "order ticket"));
        } catch (RuntimeException e) {
            log.warn("Could not find the ticket for order {} while announcing session {}: {}",
                    n.orderRef(), n.sessionRef(), e.getMessage());
        }
    }

    /** One post, one failure, no consequences beyond a log line. */
    private void postQuietly(String channelId, String lead, Map<String, Object> embed,
                             String sessionRef, String where) {
        try {
            bot.postEmbed(channelId, lead, embed);
        } catch (RuntimeException e) {
            log.warn("Could not announce session {} in the {}: {}",
                    sessionRef, where, e.getMessage());
        }
    }

    private static String inZone(java.time.Instant at, java.time.ZoneId zone) {
        if (at == null) {
            return "—";
        }
        return SESSION_TIME.format(at.atZone(zone)) + " (" + zone.getId() + ")";
    }

    /** Falls back to the business zone, labelled, rather than silently printing IST. */
    private static String inCustomerZone(CoachingBookingNotification n) {
        String zone = n.customerTimezone();
        if (zone == null || zone.isBlank()) {
            return "not recorded";
        }
        try {
            return inZone(n.startsAt(), java.time.ZoneId.of(zone));
        } catch (RuntimeException badZone) {
            // A zone string from a browser that we cannot parse. Saying so beats
            // printing a time in the wrong zone with no warning.
            return "unrecognised zone (" + zone + ")";
        }
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
        // Coaching is a session, not a delivery -- labelling it "Transfer market" told the
        // operator to look for a coin transfer that was never part of the order.
        if ("SCHEDULED_SESSION".equals(method)) return "Coaching session";
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

    /** An image to the webhook, as an attachment rather than a link it cannot open. */
    private void sendImage(String caption, byte[] image, String filename, String publicRef) {
        if (!isEnabled()) {
            log.debug("Discord disabled; would have posted the screenshot for {}", publicRef);
            return;
        }
        String boundary = "gfs" + java.util.UUID.randomUUID().toString().replace("-", "");
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("content", clamp(caption, CONTENT_LIMIT));
            payload.put("allowed_mentions", mentionAllowList());

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"payload_json\"\r\n"
                    + "Content-Type: application/json\r\n\r\n"
                    + mapper.writeValueAsString(payload) + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"files[0]\"; filename=\"" + filename + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(image);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(props.notifications().discordWebhookUrl()))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                // Status only: this body can echo the request, and the request is a
                // customer's banking screen.
                log.warn("Discord rejected the screenshot for {}: HTTP {}",
                        publicRef, response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Discord screenshot interrupted for {}", publicRef);
        } catch (Exception e) {
            // Swallowed like every other post: the image is stored whatever happens here.
            log.warn("Discord screenshot failed for {}: {}", publicRef, e.getMessage());
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
