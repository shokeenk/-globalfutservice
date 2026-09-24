package com.globalfutservice.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a Discord refusal is allowed to say out loud.
 *
 * <p>The rule matters in both directions and neither is obvious from reading one call
 * site. Withholding every body is how a 403 on slash-command registration stayed
 * undiagnosable — the status is the same for a missing OAuth scope and a mismatched
 * application id, and only the body tells them apart. Echoing every body is how an order
 * reference, a customer's name or the caption on a payment screenshot ends up in a log
 * aggregator, because Discord quotes the offending request back inside some errors.
 */
class DiscordBotClientTest {

    /** A real Discord refusal, shortened; the two fields that matter are kept. */
    private static final String ERROR_BODY =
            "{\"message\": \"Missing Access\", \"code\": 50001}";

    @Nested
    class StatusOnly {

        @Test
        @DisplayName("withholds the body even when Discord sent one")
        void withholdsBody() {
            String message = DiscordBotClient.failureMessage(
                    "create channel for GFS-26-AB12CD34", 403, ERROR_BODY,
                    DiscordBotClient.Detail.STATUS_ONLY);

            assertThat(message).isEqualTo(
                    "Discord refused to create channel for GFS-26-AB12CD34: HTTP 403");
            assertThat(message).doesNotContain("Missing Access").doesNotContain("50001");
        }

        @Test
        @DisplayName("still names the call, which is what points at the missing permission")
        void namesTheCall() {
            assertThat(DiscordBotClient.failureMessage(
                    "post to 123", 403, ERROR_BODY, DiscordBotClient.Detail.STATUS_ONLY))
                    .contains("post to 123");
        }
    }

    @Nested
    class WithBody {

        @Test
        @DisplayName("includes the reason Discord gave")
        void includesBody() {
            String message = DiscordBotClient.failureMessage(
                    "register slash commands", 403, ERROR_BODY,
                    DiscordBotClient.Detail.WITH_BODY);

            // The code is the whole point: 50001 is a missing applications.commands
            // scope, 20012 is an application id that does not own the token.
            assertThat(message)
                    .contains("HTTP 403")
                    .contains("Missing Access")
                    .contains("50001");
        }

        @Test
        @DisplayName("says so rather than trailing off when Discord sent nothing")
        void handlesEmptyBody() {
            assertThat(DiscordBotClient.failureMessage(
                    "register slash commands", 403, "", DiscordBotClient.Detail.WITH_BODY))
                    .endsWith("(no body)");
            assertThat(DiscordBotClient.failureMessage(
                    "register slash commands", 403, null, DiscordBotClient.Detail.WITH_BODY))
                    .endsWith("(no body)");
            assertThat(DiscordBotClient.failureMessage(
                    "register slash commands", 403, "   ", DiscordBotClient.Detail.WITH_BODY))
                    .endsWith("(no body)");
        }

        @Test
        @DisplayName("caps a body that is not a small error object")
        void clampsLongBody() {
            String flood = "x".repeat(5000);

            String message = DiscordBotClient.failureMessage(
                    "register slash commands", 500, flood,
                    DiscordBotClient.Detail.WITH_BODY);

            // A gateway timeout can answer with an HTML page rather than Discord's own
            // error object, and one of those per boot is enough to bury a log.
            assertThat(message.length()).isLessThan(1100);
            assertThat(message).endsWith("…");
        }
    }
}
