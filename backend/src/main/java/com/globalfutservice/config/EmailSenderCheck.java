package com.globalfutservice.config;

import java.util.Locale;
import java.util.Optional;

/**
 * Can this deployment actually send mail, and as whom?
 *
 * <p>Two failures hide here, and both are quiet. The first is a relay that is not there:
 * the development defaults point at a mail catcher on the loopback interface, and left in
 * place on a deployment they mean every send fails at connect. The second is a relay that
 * is there but will not let this account be the sender it claims — an SMTP relay
 * authenticates one account and decides what that account may claim to be. Gmail rewrites
 * a {@code From} it has not verified to whichever account signed in; stricter relays
 * refuse the message.
 *
 * <p>Neither surfaces as a crash. Each send is attempted, caught and logged as one line
 * among thousands, so a shop can run for months believing its customers are being emailed.
 * That is the shape of failure {@link BootstrapRunner} exists to shout about, and it is
 * checked at startup rather than on first send because the first send is a real
 * customer's order confirmation.
 *
 * <p>Kept apart from the runner so the rules can be tested without a Spring context.
 */
public final class EmailSenderCheck {

    private EmailSenderCheck() {
    }

    /**
     * The warning to print at startup, or empty when the configuration is coherent.
     *
     * @param emailEnabled {@code gfs.notifications.email-enabled}
     * @param from         {@code GFS_EMAIL_FROM}, the address customers see
     * @param smtpUser     {@code GFS_SMTP_USER}, the account that authenticates
     * @param smtpHost     {@code GFS_SMTP_HOST}, the relay
     * @param publicUrl    {@code GFS_PUBLIC_URL} — what tells a deployment apart from a
     *                     laptop, and so whether a loopback relay is plausible
     */
    public static Optional<String> problem(boolean emailEnabled, String from,
                                           String smtpUser, String smtpHost,
                                           String publicUrl) {
        if (!emailEnabled) {
            return Optional.empty();
        }
        if (isBlank(smtpHost)) {
            return Optional.of("  - Email is enabled but GFS_SMTP_HOST is empty. Every "
                    + "message is dropped before it reaches a relay.\n");
        }
        if (isLoopback(smtpHost) && !isLocal(publicUrl)) {
            return Optional.of("""
                      - Email is enabled, but the relay is %s — the loopback address — on \
                    a deployment serving %s. Nothing is listening there. Every message \
                    fails at connect, so no customer has received an order email and no \
                    operator has received an alert.
                        These are the local-development defaults. Point GFS_SMTP_HOST, \
                    GFS_SMTP_PORT, GFS_SMTP_USER, GFS_SMTP_PASSWORD, GFS_SMTP_AUTH and \
                    GFS_SMTP_TLS at a real relay, or set GFS_EMAIL_ENABLED=false so the \
                    system stops claiming mail goes out.
                    """.formatted(smtpHost, publicUrl));
        }
        if (isBlank(from) || isBlank(smtpUser)) {
            // Nothing left to compare. An unauthenticated relay that is not loopback is a
            // deliberate choice — an internal smarthost — and is not worth a warning.
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
            // there the right to send as a domain is proved in DNS, not by the login.
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

    /** Whether a relay host only ever resolves back to the machine asking. */
    private static boolean isLoopback(String host) {
        String h = host.trim().toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.startsWith("127.") || h.equals("::1")
                || h.equals("[::1]") || h.equals("0.0.0.0");
    }

    /**
     * Whether this instance is a laptop rather than a deployment.
     *
     * <p>Read from the storefront origin, because that is the one setting nobody can
     * leave at its default and still have a working site: the links inside every email
     * are built from it. A blank one counts as local, so a half-configured checkout does
     * not also raise a false alarm about mail.
     */
    private static boolean isLocal(String publicUrl) {
        if (isBlank(publicUrl)) {
            return true;
        }
        String u = publicUrl.toLowerCase(Locale.ROOT);
        return u.contains("localhost") || u.contains("127.0.0.1") || u.contains("[::1]");
    }
}
