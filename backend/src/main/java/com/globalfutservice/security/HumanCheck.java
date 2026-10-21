package com.globalfutservice.security;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Cloudflare Turnstile: the "are you a person" check on sign-up, forgot-password, and
 * sign-in after repeated failures.
 *
 * <p>The browser solves the widget and sends its token with the form; the token is checked
 * here, server-side, with Cloudflare's siteverify -- a token the server never checked is
 * worth nothing. A token is good once.
 *
 * <p><b>Not configured, not enforced.</b> Without both keys every check passes and a warning
 * is logged, so the code can ship before the keys exist. Once both are set it fails closed:
 * no token, a bad token, or Cloudflare unreachable all refuse.
 */
@Component
public class HumanCheck {

    private static final Logger log = LoggerFactory.getLogger(HumanCheck.class);

    /** Asked for, and not sent: the storefront shows the widget and the customer tries again. */
    public static final String REQUIRED = "human_check_required";
    /** Sent, and Cloudflare said no (or could not be asked). */
    public static final String FAILED = "human_check_failed";

    /** Cloudflare's call, kept apart so tests can answer it. */
    public interface Siteverify {
        String post(String url, String form) throws IOException, InterruptedException;
    }

    private final AppProperties.Turnstile config;
    private final ObjectMapper mapper;
    private final Siteverify siteverify;

    @Autowired
    public HumanCheck(AppProperties props, ObjectMapper mapper) {
        this(props.turnstile(), mapper, http());
    }

    public HumanCheck(AppProperties.Turnstile config, ObjectMapper mapper, Siteverify siteverify) {
        this.config = config;
        this.mapper = mapper;
        this.siteverify = siteverify;
        if (!enabled()) {
            log.warn("Cloudflare Turnstile is not configured (GFS_TURNSTILE_SITE_KEY and GFS_TURNSTILE_SECRET_KEY): "
                    + "sign-up, forgot-password and repeated sign-ins are not checked for a human");
        }
    }

    public boolean enabled() {
        return config != null && config.configured();
    }

    /** The site key for the storefront's widget, or null when the check is off. */
    public String siteKey() {
        return enabled() ? config.siteKey().trim() : null;
    }

    /**
     * Passes, or throws a 400 with {@link #REQUIRED} or {@link #FAILED}.
     *
     * @param what names the form in the log, never the token
     */
    public void require(String token, String ip, String what) {
        if (!enabled()) {
            log.warn("Turnstile not configured: {} accepted without a human check", what);
            return;
        }
        if (token == null || token.isBlank()) {
            throw new ApiExceptions.BadRequestException(REQUIRED, "Please complete the check to continue.");
        }
        if (!verified(token.trim(), ip)) {
            log.info("Turnstile refused a token for {}", what);
            throw new ApiExceptions.BadRequestException(FAILED, "The check did not pass. Please try it again.");
        }
    }

    private boolean verified(String token, String ip) {
        String form = "secret=" + enc(config.secretKey().trim()) + "&response=" + enc(token)
                + (ip == null || ip.isBlank() ? "" : "&remoteip=" + enc(ip));
        try {
            JsonNode answer = mapper.readTree(siteverify.post(config.verifyUrl(), form));
            return answer.path("success").asBoolean(false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException e) {
            // Unreachable or unreadable: refused rather than waved through.
            log.warn("Turnstile siteverify failed: {}", e.getMessage());
            return false;
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Siteverify http() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return (url, form) -> client.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(8))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(), HttpResponse.BodyHandlers.ofString())
                .body();
    }
}
