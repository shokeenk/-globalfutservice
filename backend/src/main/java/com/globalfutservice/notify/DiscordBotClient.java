package com.globalfutservice.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The Discord bot, for the two things a webhook cannot do: make a channel and find one.
 *
 * <p><b>Written against the REST API rather than JDA, deliberately.</b> JDA is the right
 * tool for a bot that listens -- commands, reactions, presence -- because it manages a
 * gateway websocket and its lifecycle. This bot never listens. It creates a channel and
 * posts into it, which is three REST calls, and JDA would bring a websocket stack, a
 * voice/audio dependency tree and a connection to keep alive on a free-tier instance for
 * the privilege. The three clients already in this codebase -- Razorpay, FUT Transfer,
 * Telegram -- are all hand-rolled on the JDK client for the same reason, and each says so.
 * Switching to JDA later is contained to this file.
 *
 * <p><b>Nothing here logs a response body.</b> Discord echoes the request back in some
 * errors, and the request carries a customer's name, email and payment reference.
 */
@Component
public class DiscordBotClient {

    private static final Logger log = LoggerFactory.getLogger(DiscordBotClient.class);

    private static final String API = "https://discord.com/api/v10";

    /** Discord's own ceiling on a message. */
    private static final int CONTENT_LIMIT = 2000;

    /** 0 is GUILD_TEXT. */
    private static final int TYPE_TEXT = 0;

    private final AppProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public DiscordBotClient(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Whether a ticket can be opened at all.
     *
     * <p>The category is required rather than optional. Without it a channel would still
     * be created, but at the root of the server rather than beside the existing tickets --
     * which is the arrangement this feature exists to match, so a half-configured bot
     * should fall back to the webhook rather than scatter channels.
     */
    public boolean isEnabled() {
        AppProperties.Notifications n = props.notifications();
        return present(n.discordBotToken())
                && present(n.discordGuildId())
                && present(n.discordOrderCategoryId());
    }

    /* ------------------------------------------------------------------ names --- */

    /**
     * The channel name for an order reference.
     *
     * <p>Discord lowercases names and rejects most punctuation, so doing it here means the
     * name we ask for is the name we get -- which matters because finding the channel
     * again later is done by name.
     */
    public static String channelNameFor(String publicRef) {
        String cleaned = publicRef.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        String name = "order-" + cleaned;
        return name.length() <= 100 ? name : name.substring(0, 100);
    }

    /* --------------------------------------------------------------- channels --- */

    /**
     * Creates the ticket channel and returns its id.
     *
     * @throws DiscordException when Discord refuses -- missing permission, bad category,
     *                          rate limit. The caller falls back to the webhook.
     */
    public String createTicketChannel(String publicRef) {
        AppProperties.Notifications cfg = props.notifications();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", channelNameFor(publicRef));
        body.put("type", TYPE_TEXT);
        body.put("parent_id", cfg.discordOrderCategoryId());

        JsonNode created = post(API + "/guilds/" + cfg.discordGuildId() + "/channels",
                body, "create channel for " + publicRef);

        String id = created.path("id").asText(null);
        if (id == null || id.isBlank()) {
            throw new DiscordException("Discord returned no channel id for " + publicRef);
        }
        log.info("Opened Discord ticket channel {} for order {}", id, publicRef);
        return id;
    }

    /**
     * Finds an existing ticket channel by the name we gave it.
     *
     * <p>Used when the screenshot arrives after the ticket has already been opened. Looked
     * up rather than stored: the name is derived from the order reference, so it is already
     * a stable key, and a column holding an id that Discord can delete underneath us would
     * be a second source of truth that goes stale silently.
     */
    public Optional<String> findTicketChannel(String publicRef) {
        AppProperties.Notifications cfg = props.notifications();
        String wanted = channelNameFor(publicRef);

        JsonNode channels = get(API + "/guilds/" + cfg.discordGuildId() + "/channels",
                "find channel for " + publicRef);

        if (channels != null && channels.isArray()) {
            for (JsonNode channel : channels) {
                if (wanted.equals(channel.path("name").asText(null))) {
                    return Optional.ofNullable(channel.path("id").asText(null));
                }
            }
        }
        return Optional.empty();
    }

    /* --------------------------------------------------------------- messages --- */

    public void postMessage(String channelId, String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", clamp(content, CONTENT_LIMIT));
        // Nothing in an order alert should be able to ping a server. The content is built
        // from customer-supplied fields -- a name, a reference -- and "@everyone" in one
        // of them would otherwise notify the whole guild.
        body.put("allowed_mentions", Map.of("parse", java.util.List.of()));

        post(API + "/channels/" + channelId + "/messages", body, "post to " + channelId);
    }

    /**
     * Posts the payment screenshot into the ticket.
     *
     * <p>Uploaded as an attachment rather than linked, because a link to the admin console
     * is useless from a phone that is not signed into it -- and the operator reading this
     * ticket is usually on a phone.
     */
    public void postImage(String channelId, byte[] image, String filename, String caption) {
        String boundary = "gfs" + java.util.UUID.randomUUID().toString().replace("-", "");
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("content", clamp(caption, CONTENT_LIMIT));
            payload.put("allowed_mentions", Map.of("parse", java.util.List.of()));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
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
                    .uri(URI.create(API + "/channels/" + channelId + "/messages"))
                    .header("Authorization", "Bot " + props.notifications().discordBotToken())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new DiscordException("Discord rejected the screenshot for channel "
                        + channelId + ": HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiscordException("Interrupted uploading a screenshot to " + channelId);
        } catch (DiscordException e) {
            throw e;
        } catch (Exception e) {
            throw new DiscordException("Could not upload the screenshot to " + channelId
                    + ": " + e.getMessage());
        }
    }

    /* -------------------------------------------------------------- transport --- */

    private JsonNode post(String url, Map<String, Object> body, String what) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bot " + props.notifications().discordBotToken())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            return send(request, what);
        } catch (DiscordException e) {
            throw e;
        } catch (Exception e) {
            throw new DiscordException("Could not " + what + ": " + e.getMessage());
        }
    }

    private JsonNode get(String url, String what) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bot " + props.notifications().discordBotToken())
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            return send(request, what);
        } catch (DiscordException e) {
            throw e;
        } catch (Exception e) {
            throw new DiscordException("Could not " + what + ": " + e.getMessage());
        }
    }

    private JsonNode send(HttpRequest request, String what) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            /*
             * Status only, never the body. Discord echoes the offending request back in
             * some errors, and this request carries a customer's name, email and payment
             * reference. 403 here almost always means the bot lacks Manage Channels in
             * that category, which is granted in Discord and not in this repository.
             */
            throw new DiscordException("Discord refused to " + what
                    + ": HTTP " + response.statusCode());
        }
        return mapper.readTree(response.body());
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String clamp(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit - 1) + "…";
    }

    /** Anything Discord refused. Carries a reason safe to log; never a payload. */
    public static class DiscordException extends RuntimeException {
        public DiscordException(String message) {
            super(message);
        }
    }
}
