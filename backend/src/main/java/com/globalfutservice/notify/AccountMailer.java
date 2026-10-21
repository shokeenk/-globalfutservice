package com.globalfutservice.notify;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.globalfutservice.notify.email.AccountEmails;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

/**
 * Sends the account security emails, off the request thread.
 *
 * <p>Asynchronous for a reason beyond speed: "Forgot password?" answers the same whether or
 * not the address has an account, and an answer that waited on the mail server only when
 * there was an account to mail would say so through its timing.
 *
 * <p>Never consults marketing consent. These are security notices about the account itself.
 */
@Component
public class AccountMailer {

    private final EmailNotifier email;

    public AccountMailer(EmailNotifier email) {
        this.email = email;
    }

    /** The link in the email: the storefront's reset page, carrying the raw token. */
    public String resetUrl(String rawToken) {
        return email.publicUrl() + "/reset-password?token="
                + UriUtils.encodeQueryParam(rawToken, StandardCharsets.UTF_8);
    }

    @Async
    public void sendResetLink(String to, String accountPublicId, AccountEmails.Lang lang,
                              String rawToken, long minutes) {
        email.sendHtmlTo(to, AccountEmails.resetLink(lang, email.brand(), resetUrl(rawToken), minutes),
                "password reset for " + accountPublicId);
    }

    @Async
    public void sendVerification(String to, String accountPublicId, AccountEmails.Lang lang,
                                 String rawToken, long hours) {
        String url = email.publicUrl() + "/confirm-email?token="
                + UriUtils.encodeQueryParam(rawToken, StandardCharsets.UTF_8);
        email.sendHtmlTo(to, AccountEmails.verifyEmail(lang, email.brand(), url, hours),
                "email verification for " + accountPublicId);
    }

    @Async
    public void sendPasswordChanged(String to, String accountPublicId, AccountEmails.Lang lang,
                                    Instant changedAt) {
        email.sendHtmlTo(to, AccountEmails.passwordChanged(lang, email.brand(),
                email.publicUrl() + "/forgot-password", changedAt),
                "password changed for " + accountPublicId);
    }
}
