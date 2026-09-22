package com.globalfutservice.config;

import java.util.Locale;
import java.util.Optional;

/**
 * Is the address customers will see one this deployment is allowed to send from?
 *
 * <p>An SMTP relay authenticates one account, and it will not let that account claim to
 * be somebody else. Gmail rewrites a {@code From} it has not verified to whichever
 * account signed in; stricter relays reject the message outright. Either way the address
 * configured here is not the address that arrives — and nothing in the send path notices.
 * The send succeeds, the log line says the mail went out, and the customer receives a
 * message from a stranger, or nothing at all.
 *
 * <p>That is the shape of failure {@link BootstrapRunner} exists to shout about: not a
 * crash, but a silent divergence between what the configuration says and what the world
 * does. It is checked at startup rather than on first send because the first send is a
 * real customer's order confirmation.
 *
 * <p>Kept apart from the runner so the rule can be tested without a Spring context.
 */
public final class EmailSenderCheck {

    private EmailSenderCheck() {
    }

    /**
     * The warning to print at startup, or empty when the sender is coherent.
     *
     * @param emailEnabled {@code gfs.notifications.email-enabled}
     * @param from         {@code GFS_EMAIL_FROM}, the address customers see
     * @param smtpUser     {@code GFS_SMTP_USER}, the account that authenticates
     * @param smtpHost     {@code GFS_SMTP_HOST}, the relay
     */
    public static Optional<String> problem(boolean emailEnabled, String from,
                                           String smtpUser, String smtpHost) {
        if (!emailEnabled) {
            return Optional.empty();
        }
        if (isBlank(smtpHost)) {
            return Optional.of("  - Email is enabled but GFS_SMTP_HOST is empty. Every "
                    + "message is dropped before it reaches a relay.\n");
        }
        if (isBlank(from) || isBlank(smtpUser)) {
            // Nothing to compare. An unauthenticated relay is how local development
            // talks to Mailpit, and is not worth a warning.
            return Optional.empty();
        }

        String fromDomain = domainOf(from);
        String userDomain = domainOf(smtpUser);
        if (fromDomain == null) {
            return Optional.of("  - GFS_EMAIL_FROM is not an email address: " + from + "\n");
        }
        if (userDomain == null || fromDomain.equals(userDomain)) {
            // A username without a domain is normal for a transactional provider —
            // SendGrid authenticates as the literal "apikey", Resend as "resend" — and
            // there the sending domain is proved by DNS, not by the login. Nothing to say.
            return Optional.empty();
        }

        return Optional.of("""
                  - Email is enabled, but the address customers will see does not belong \
                to the account that sends it:
                      GFS_EMAIL_FROM  %s
                      GFS_SMTP_USER   %s
                    A relay will not let an account send as a domain it has not verified. \
                Gmail silently rewrites the From to the account above, so the customer \
                sees a personal address on their order confirmation; stricter relays \
                reject the message and it never arrives.
                    Either set GFS_EMAIL_FROM to an address this account is authorised to \
                send as, or move to a provider where the sending domain is verified with \
                SPF and DKIM.
                """.formatted(from, smtpUser));
    }

    /** Lowercased text after the last {@code @}, or null when there is none. */
    private static String domainOf(String address) {
        int at = address.lastIndexOf('@');
        if (at < 0 || at == address.length() - 1) {
            return null;
        }
        return address.substring(at + 1).trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
