package com.globalfutservice.credentials.web;

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
            @Email(message = "That does not look like an email address")
            @Size(max = 255)
            String eaEmail,

            @NotBlank(message = "EA password is required")
            @Size(max = 255)
            String eaPassword,

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
