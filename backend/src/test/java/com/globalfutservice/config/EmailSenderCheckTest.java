package com.globalfutservice.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The startup check that catches mail which is enabled but reaches nobody.
 *
 * <p>Worth testing rather than eyeballing because both of its mistakes are expensive in
 * opposite directions. Missing a real fault is how a shop emails nobody for months;
 * warning about a healthy configuration is how an operator learns to scroll past the
 * startup warnings, which costs every other check in that block.
 */
class EmailSenderCheckTest {

    private static final String DEPLOYED = "https://globalfutservices.com";
    private static final String LAPTOP = "http://localhost:5173";

    @Test
    @DisplayName("says nothing when email is switched off")
    void silentWhenDisabled() {
        assertThat(EmailSenderCheck.problem(
                false, "orders@example.com", "someone@gmail.com", "smtp.gmail.com", DEPLOYED))
                .isEmpty();
    }

    /**
     * The development defaults left on a deployment.
     *
     * <p>This is the case the first version of this check was blind to: it treated an
     * unauthenticated loopback relay as a deliberate local setup and returned early,
     * which is right on a laptop and wrong on the machine serving customers.
     */
    @Nested
    class RelayThatIsNotThere {

        @Test
        @DisplayName("warns when a deployment points at the loopback interface")
        void warnsOnLoopbackInProduction() {
            String warning = EmailSenderCheck.problem(
                    true, "orders@example.com", "", "localhost", DEPLOYED).orElseThrow();

            assertThat(warning)
                    .contains("localhost")
                    .contains(DEPLOYED)
                    .contains("GFS_EMAIL_ENABLED=false");
        }

        @Test
        @DisplayName("catches the numeric forms too")
        void warnsOnNumericLoopback() {
            assertThat(EmailSenderCheck.problem(
                    true, "orders@example.com", "", "127.0.0.1", DEPLOYED)).isPresent();
            assertThat(EmailSenderCheck.problem(
                    true, "orders@example.com", "", "::1", DEPLOYED)).isPresent();
            assertThat(EmailSenderCheck.problem(
                    true, "orders@example.com", "", "0.0.0.0", DEPLOYED)).isPresent();
        }

        @Test
        @DisplayName("stays quiet on a laptop, where a mail catcher is the point")
        void silentOnLaptop() {
            assertThat(EmailSenderCheck.problem(
                    true, "orders@example.com", "", "localhost", LAPTOP)).isEmpty();
        }

        @Test
        @DisplayName("treats an unset public URL as a laptop rather than guessing")
        void silentWhenPublicUrlUnset() {
            assertThat(EmailSenderCheck.problem(
                    true, "orders@example.com", "", "localhost", "")).isEmpty();
        }

        @Test
        @DisplayName("outranks the sender mismatch, because it is the reason nothing sends")
        void loopbackReportedBeforeMismatch() {
            // Both faults are present. The unreachable relay is the one to fix first:
            // correcting the From address changes nothing while no connection is made.
            String warning = EmailSenderCheck.problem(
                    true, "orders@globalfutservices.com", "someone@gmail.com",
                    "localhost", DEPLOYED).orElseThrow();

            assertThat(warning).contains("loopback").doesNotContain("GFS_SMTP_USER   ");
        }
    }

    @Nested
    class SenderNobodyAuthorised {

        @Test
        @DisplayName("says nothing when the sender belongs to the account sending it")
        void silentWhenDomainsAgree() {
            assertThat(EmailSenderCheck.problem(
                    true, "orders@example.com", "orders@example.com",
                    "smtp.example.com", DEPLOYED)).isEmpty();
            // Same domain, different mailbox: the relay is still authorised for it.
            assertThat(EmailSenderCheck.problem(
                    true, "orders@example.com", "noreply@EXAMPLE.com",
                    "smtp.example.com", DEPLOYED)).isEmpty();
        }

        @Test
        @DisplayName("names both addresses when the From is not the account's to use")
        void warnsOnMismatch() {
            String warning = EmailSenderCheck.problem(
                    true, "orders@globalfutservices.com", "someone@gmail.com",
                    "smtp.gmail.com", DEPLOYED).orElseThrow();

            // Both halves, because a warning naming only one leaves the reader to go
            // and find the other before they can act on it.
            assertThat(warning)
                    .contains("orders@globalfutservices.com")
                    .contains("someone@gmail.com")
                    .contains("GFS_EMAIL_FROM");
        }

        @Test
        @DisplayName("stays quiet for a provider that authenticates with a key, not an address")
        void silentForApiKeyUsernames() {
            // SendGrid signs in as the literal "apikey", Resend as "resend". There the
            // right to send as a domain is proved in DNS, not by the login, so comparing
            // the two would fire on every correctly configured provider.
            assertThat(EmailSenderCheck.problem(
                    true, "orders@globalfutservices.com", "apikey",
                    "smtp.sendgrid.net", DEPLOYED)).isEmpty();
            assertThat(EmailSenderCheck.problem(
                    true, "orders@globalfutservices.com", "resend",
                    "smtp.resend.com", DEPLOYED)).isEmpty();
        }

        @Test
        @DisplayName("stays quiet for an internal smarthost that wants no credentials")
        void silentForUnauthenticatedSmarthost() {
            assertThat(EmailSenderCheck.problem(
                    true, "orders@example.com", "", "mail.internal", DEPLOYED)).isEmpty();
        }
    }

    @Test
    @DisplayName("warns when email is on but there is no relay to send to")
    void warnsOnMissingHost() {
        assertThat(EmailSenderCheck.problem(
                true, "orders@globalfutservices.com", "someone@gmail.com", " ", DEPLOYED)
                .orElseThrow())
                .contains("GFS_SMTP_HOST");
    }

    @Test
    @DisplayName("warns when the From is not an address at all")
    void warnsOnMalformedFrom() {
        assertThat(EmailSenderCheck.problem(
                true, "Global FUT Services", "someone@gmail.com", "smtp.gmail.com", DEPLOYED)
                .orElseThrow())
                .contains("not an email address");
    }
}
