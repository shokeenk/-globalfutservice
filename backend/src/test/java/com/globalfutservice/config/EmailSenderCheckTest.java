package com.globalfutservice.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The startup check that catches a sender nobody is allowed to be.
 *
 * <p>Worth testing rather than eyeballing because both of its mistakes are expensive in
 * opposite directions. Missing a real mismatch is how order confirmations go out from a
 * personal address for months; warning about a healthy configuration is how an operator
 * learns to scroll past the startup warnings, which costs every other check in the block.
 */
class EmailSenderCheckTest {

    @Test
    @DisplayName("says nothing when email is switched off")
    void silentWhenDisabled() {
        assertThat(EmailSenderCheck.problem(
                false, "orders@example.com", "someone@gmail.com", "smtp.gmail.com"))
                .isEmpty();
    }

    @Test
    @DisplayName("says nothing when the sender belongs to the account sending it")
    void silentWhenDomainsAgree() {
        assertThat(EmailSenderCheck.problem(
                true, "orders@example.com", "orders@example.com", "smtp.example.com"))
                .isEmpty();
        // Same domain, different mailbox: the relay is still authorised for it.
        assertThat(EmailSenderCheck.problem(
                true, "orders@example.com", "noreply@EXAMPLE.com", "smtp.example.com"))
                .isEmpty();
    }

    @Test
    @DisplayName("names both addresses when the From is not the account's to use")
    void warnsOnMismatch() {
        String warning = EmailSenderCheck.problem(
                true, "orders@globalfutservices.com", "someone@gmail.com", "smtp.gmail.com")
                .orElseThrow();

        // Both halves of the mismatch, because a warning naming only one of them leaves
        // the reader to go and find the other before they can act on it.
        assertThat(warning)
                .contains("orders@globalfutservices.com")
                .contains("someone@gmail.com")
                .contains("GFS_EMAIL_FROM");
    }

    @Test
    @DisplayName("stays quiet for a provider that authenticates with a key, not an address")
    void silentForApiKeyUsernames() {
        // SendGrid signs in as the literal "apikey", Resend as "resend". There the right
        // to send as a domain is proved in DNS, not by the login, so comparing the two
        // would fire on every correctly configured provider.
        assertThat(EmailSenderCheck.problem(
                true, "orders@globalfutservices.com", "apikey", "smtp.sendgrid.net"))
                .isEmpty();
        assertThat(EmailSenderCheck.problem(
                true, "orders@globalfutservices.com", "resend", "smtp.resend.com"))
                .isEmpty();
    }

    @Test
    @DisplayName("stays quiet for an unauthenticated local relay")
    void silentForLocalDevelopment() {
        assertThat(EmailSenderCheck.problem(
                true, "orders@globalfutservices.com", "", "localhost"))
                .isEmpty();
    }

    @Test
    @DisplayName("warns when email is on but there is no relay to send to")
    void warnsOnMissingHost() {
        assertThat(EmailSenderCheck.problem(
                true, "orders@globalfutservices.com", "someone@gmail.com", " ")
                .orElseThrow())
                .contains("GFS_SMTP_HOST");
    }

    @Test
    @DisplayName("warns when the From is not an address at all")
    void warnsOnMalformedFrom() {
        assertThat(EmailSenderCheck.problem(
                true, "Global FUT Services", "someone@gmail.com", "smtp.gmail.com")
                .orElseThrow())
                .contains("not an email address");
    }
}
