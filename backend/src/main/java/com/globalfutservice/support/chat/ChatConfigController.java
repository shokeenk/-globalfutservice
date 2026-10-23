package com.globalfutservice.support.chat;

import com.globalfutservice.config.AppProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * The live chat's public settings, for the storefront to read when it starts: tawk.to's
 * property id and its two widget ids.
 *
 * <p>Served at runtime, like the Turnstile site key, rather than built into the storefront:
 * its Docker build only ever received the variables its Dockerfile declared, so ids set as
 * build variables came out empty and the chat never appeared (2026-10-23). These ids are
 * public -- every page that loads the chat carries them. The Secure Mode key never leaves
 * the server and is not here.
 */
@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "Live chat", description = "tawk.to's public ids")
public class ChatConfigController {

    /** tawk.to's ids are plain tokens; anything else is treated as not set rather than sent on. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final AppProperties.Tawk tawk;

    public ChatConfigController(AppProperties props) {
        this.tawk = props.tawk();
    }

    /** Each id, or null when it is not set (or malformed). */
    public record ChatConfig(String propertyId, String embedWidgetId, String floatingWidgetId) {
    }

    @GetMapping("/config")
    @Operation(summary = "tawk.to's property and widget ids (public), or nulls when the chat is not set up")
    public ResponseEntity<ChatConfig> config() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(new ChatConfig(clean(tawk.propertyId()), clean(tawk.embedWidgetId()),
                        clean(tawk.floatingWidgetId())));
    }

    private static String clean(String id) {
        String value = id == null ? "" : id.trim();
        return ID.matcher(value).matches() ? value : null;
    }
}
