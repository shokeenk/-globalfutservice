package com.globalfutservice.credentials.web;

import com.globalfutservice.credentials.SignInRules;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class CredentialDtos {

    private CredentialDtos() {
    }

    /**
     * An EA sign-in, in transit, for exactly as long as it takes to seal it.
     *
     * <p>{@code toString} is overridden on purpose. Spring's request logging, most APM
     * agents and any {@code log.debug("payload {}", request)} will call it, and the
     * default record implementation would print the password in clear. Overriding it is
     * a two-line change that removes a whole category of accidental disclosure.
     *
     * <p>The four acknowledgements at the bottom are not paperwork. Each one is a failed
     * order turned into a checkbox: an account still signed in kicks the trader's
     * session, a locked transfer market makes the order impossible, and unassigned items
     * block transfers outright. Asking up front is far cheaper than a support thread
     * afterwards.
     */
    public record SubmitCredentialsRequest(
            @NotBlank(message = "EA email is required")
            @Email(regexp = SignInRules.EMAIL_REGEX, message = "That does not look like an email address")
            @Size(max = 255)
            String eaEmail,

            @NotBlank(message = "EA password is required")
            /*
             * Eight is the partner's minimum, not ours. Without it a shorter password was
             * accepted at checkout and refused by the partner at release -- after the
             * customer had paid, and with an operator left holding an order that could
             * not go out.
             */
            @Size(min = SignInRules.PASSWORD_MIN, max = 255,
                    message = "An EA password is at least 8 characters — please check it")
            String eaPassword,

            /*
             * How many, and in what shape, is checked by SignInRules.backupCodes before
             * anything is stored: the number required is configuration, which an annotation
             * cannot read. These bounds only keep an absurd payload out of that check.
             */
            @Size(max = 12, message = "Twelve backup codes is the most EA issues")
            List<@Size(max = 32) String> backupCodes,

            @Size(max = 64)
            String platformHandle,

            @Size(max = 500)
            String note,

            @AssertTrue(message = "Please sign out of your account everywhere first")
            boolean acknowledgedSignedOut,

            @AssertTrue(message = "Your transfer market must be unlocked")
            boolean acknowledgedMarketUnlocked,

            @AssertTrue(message = "Please clear your unassigned items first")
            boolean acknowledgedItemsClear,

            @AssertTrue(message = "Please accept the terms to continue")
            boolean acceptedTerms) {

        /** The same request with its backup codes as SignInRules returned them. */
        public SubmitCredentialsRequest withBackupCodes(List<String> codes) {
            return new SubmitCredentialsRequest(eaEmail, eaPassword, codes, platformHandle, note,
                    acknowledgedSignedOut, acknowledgedMarketUnlocked, acknowledgedItemsClear, acceptedTerms);
        }

        @Override
        public String toString() {
            return "SubmitCredentialsRequest[redacted]";
        }
    }

    /** What an operator sees. Returned once, over TLS, and never cached. */
    public record RevealedCredentials(
            String eaEmail,
            String eaPassword,
            List<String> backupCodes,
            String platformHandle,
            String note) {

        @Override
        public String toString() {
            return "RevealedCredentials[redacted]";
        }
    }

    public record VaultStatus(
            boolean present,
            boolean purged,
            String purgeAfter,
            int accessedCount) {
    }
}
