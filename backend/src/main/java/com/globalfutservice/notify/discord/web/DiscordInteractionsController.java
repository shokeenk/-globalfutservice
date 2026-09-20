package com.globalfutservice.notify.discord.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.notify.discord.DiscordSignature;
import com.globalfutservice.notify.discord.DiscordVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where Discord delivers slash commands.
 *
 * <p><b>Why an HTTP endpoint rather than a bot that listens.</b> A bot that watches a
 * channel for people typing needs a gateway connection — a WebSocket held open for the
 * life of the process, a client library to speak it, and, to read what people actually
 * wrote, Discord's Message Content privileged intent, which has to be applied for and
 * granted. None of that survives a service that sleeps when idle. Discord's other option
 * is this one: it posts each interaction here over ordinary HTTPS, signed. No connection
 * to hold, nothing to reconnect after a deploy, and no privileged intent, because a slash
 * command's arguments are given to us rather than overheard.
 *
 * <p><b>Three seconds.</b> Discord closes the interaction if no reply has been written
 * within three seconds, so everything this endpoint does has to fit inside that — which
 * it does, being a database read and one Discord call. If that ever stops being true the
 * answer is a deferred response (type 5) followed by a webhook edit, not a longer wait.
 *
 * <p>The reply is ephemeral: only the person who ran the command sees it. That matters
 * because the failure messages mention order references, and {@code #verify-order} is a
 * channel everyone in the server can read.
 */
@RestController
@RequestMapping("/api/v1/discord")
@Tag(name = "Discord", description = "Slash commands from the GFS Discord server")
public class DiscordInteractionsController {

    private static final Logger log =
            LoggerFactory.getLogger(DiscordInteractionsController.class);

    /** Discord's interaction types, of which we handle two. */
    private static final int PING = 1;
    private static final int APPLICATION_COMMAND = 2;

    /** Response types. */
    private static final int PONG = 1;
    private static final int CHANNEL_MESSAGE_WITH_SOURCE = 4;

    /** Message flags: 64 is EPHEMERAL — visible only to the person who ran the command. */
    private static final int EPHEMERAL = 64;

    private final AppProperties props;
    private final DiscordVerificationService verification;
    private final ObjectMapper mapper;

    public DiscordInteractionsController(AppProperties props,
                                         DiscordVerificationService verification,
                                         ObjectMapper mapper) {
        this.props = props;
        this.verification = verification;
        this.mapper = mapper;
    }

    /**
     * One interaction.
     *
     * <p>The body is taken as a {@code String} rather than a parsed object on purpose:
     * the signature covers the exact bytes Discord sent, and a body that Jackson has
     * parsed and re-serialised is a different sequence of bytes that will not verify.
     */
    @PostMapping(value = "/interactions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Discord interactions callback",
            description = "Signed by Discord with Ed25519. Not callable by anything else.")
    public ResponseEntity<String> interactions(
            @RequestHeader(value = "X-Signature-Ed25519", required = false) String signature,
            @RequestHeader(value = "X-Signature-Timestamp", required = false) String timestamp,
            @RequestBody String body) {

        if (!DiscordSignature.verify(props.notifications().discordPublicKey(),
                signature, timestamp, body)) {
            /*
             * 401 specifically. Discord probes a newly configured endpoint with a
             * deliberately invalid signature and will not accept it unless that probe is
             * rejected — an endpoint that answers 200 to everything fails registration.
             */
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("invalid signature");
        }

        try {
            JsonNode interaction = mapper.readTree(body);
            int type = interaction.path("type").asInt();

            if (type == PING) {
                return json(Map.of("type", PONG));
            }
            if (type == APPLICATION_COMMAND) {
                return json(handleCommand(interaction));
            }
            // Buttons, selects, autocomplete: nothing here registers any, so anything
            // else is noise rather than a case to handle.
            return json(reply("That command isn't one I know."));
        } catch (Exception e) {
            log.error("Could not handle a Discord interaction", e);
            return json(reply("Something went wrong on our side. Please message support."));
        }
    }

    private Map<String, Object> handleCommand(JsonNode interaction) {
        String name = interaction.path("data").path("name").asText("");
        if (!"verify".equals(name)) {
            return reply("That command isn't one I know.");
        }

        /*
         * The member object is present for a command run inside a server and absent for
         * one run in a DM, where `user` sits at the top level instead. Verification needs
         * the person to be in the server — there is no channel to let them into
         * otherwise — so a DM is answered with the reason rather than a failure.
         */
        JsonNode member = interaction.path("member");
        if (member.isMissingNode() || member.isNull()) {
            return reply("Run this in the server rather than in a DM — I can only open a "
                    + "ticket for someone who has joined.");
        }
        String userId = member.path("user").path("id").asText(null);
        String username = member.path("user").path("username").asText(null);
        if (userId == null || userId.isBlank()) {
            return reply("I couldn't read your Discord account. Please message support.");
        }

        String orderRef = optionValue(interaction, "order");

        DiscordVerificationService.Result result =
                verification.verify(userId, username, orderRef);

        if (result.granted() && result.channelId() != null) {
            return reply(result.message() + "\n\n<#" + result.channelId() + ">");
        }
        return reply(result.message());
    }

    /** Pulls one named option out of the command's arguments. */
    private static String optionValue(JsonNode interaction, String optionName) {
        for (JsonNode option : interaction.path("data").path("options")) {
            if (optionName.equals(option.path("name").asText())) {
                return option.path("value").asText("");
            }
        }
        return "";
    }

    private static Map<String, Object> reply(String content) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", content);
        data.put("flags", EPHEMERAL);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", CHANNEL_MESSAGE_WITH_SOURCE);
        out.put("data", data);
        return out;
    }

    private ResponseEntity<String> json(Map<String, Object> payload) {
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("Could not serialise a Discord reply", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{}");
        }
    }
}
