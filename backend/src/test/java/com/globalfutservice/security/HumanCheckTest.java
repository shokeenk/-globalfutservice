package com.globalfutservice.security;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cloudflare Turnstile, checked on the server -- and skipped, with a warning, until it is configured. */
class HumanCheckTest {

    private static final String URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private final List<String> sent = new ArrayList<>();

    private HumanCheck check(String siteKey, String secret, String answer) {
        return new HumanCheck(new AppProperties.Turnstile(siteKey, secret, URL), new ObjectMapper(), (url, form) -> {
            assertThat(url).isEqualTo(URL);
            sent.add(form);
            if (answer == null) throw new IOException("unreachable");
            return answer;
        });
    }

    private static String code(Throwable t) {
        return ((ApiExceptions.BadRequestException) t).code();
    }

    @Test
    @DisplayName("not configured: every check passes, Cloudflare is never called, and no site key is handed out")
    void skippedWhenNotConfigured() {
        for (HumanCheck off : List.of(check(null, null, "{}"), check("site", "", "{}"), check(" ", "secret", "{}"),
                new HumanCheck(null, new ObjectMapper(), (u, f) -> { throw new AssertionError(); }))) {
            assertThat(off.enabled()).isFalse();
            assertThat(off.siteKey()).isNull();
            assertThatCode(() -> off.require(null, "198.51.100.1", "sign-up")).doesNotThrowAnyException();
            assertThatCode(() -> off.require("anything", "198.51.100.1", "sign-up")).doesNotThrowAnyException();
        }
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("configured: no token is asked for, a token Cloudflare accepts passes")
    void verifiedWithCloudflare() {
        HumanCheck on = check("0x4AAA-site", "0x4AAA-secret", "{\"success\":true}");
        assertThat(on.siteKey()).isEqualTo("0x4AAA-site");

        assertThatThrownBy(() -> on.require(" ", "198.51.100.1", "sign-up"))
                .satisfies(t -> assertThat(code(t)).isEqualTo(HumanCheck.REQUIRED));
        assertThat(sent).isEmpty();

        on.require("tok&en", "198.51.100.1", "sign-up");
        assertThat(sent).containsExactly("secret=0x4AAA-secret&response=tok%26en&remoteip=198.51.100.1");
    }

    @Test
    @DisplayName("configured: refused by Cloudflare, or Cloudflare unreachable or unreadable, fails closed")
    void failsClosed() {
        for (String answer : new String[] {"{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}",
                null, "not json", "{}"}) {
            HumanCheck on = check("site", "secret", answer);
            assertThatThrownBy(() -> on.require("token", null, "forgot password"))
                    .satisfies(t -> assertThat(code(t)).isEqualTo(HumanCheck.FAILED));
        }
    }
}
