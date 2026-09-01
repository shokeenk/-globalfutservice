package com.globalfutservice.identity.web;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @Email(message = "That does not look like an email address")
            @NotBlank(message = "Email is required")
            @Size(max = 255)
            String email,

            /**
             * Twelve characters minimum, with no composition rules.
             *
             * <p>Length beats character classes: "Xy7!" satisfies every mixed-case,
             * digit-and-symbol policy ever written and is trivially guessable, while a
             * four-word passphrase satisfies none of them and is not. NIST dropped the
             * composition requirements for exactly this reason.
             *
             * <p>Twelve rather than the NIST floor of eight, deliberately. Every character
             * added multiplies the cost of an offline attack on a leaked database by
             * roughly two orders of magnitude, and this application stores order history
             * that defends chargebacks and, briefly, EA sign-ins. A passphrase reaches
             * twelve without effort, so the friction lands on people picking a mangled
             * word — which is the case the length is there to catch.
             */
            @NotBlank(message = "Password is required")
            @Size(min = 12, max = 128, message = "Use at least 12 characters — a short phrase works well")
            String password,

            @Size(max = 80)
            String displayName,

            @Size(max = 20)
            @Pattern(regexp = "^[+0-9 ()-]*$", message = "That does not look like a phone number")
            String phone,

            @Size(max = 32)
            @Pattern(regexp = "^[A-Za-z0-9_-]*$", message = "Codes are letters and numbers only")
            String referralCode,

            @AssertTrue(message = "Please accept the terms to create an account")
            boolean acceptedTerms) {

        @Override
        public String toString() {
            return "RegisterRequest[email=" + email + ", redacted]";
        }
    }

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password) {

        @Override
        public String toString() {
            return "LoginRequest[email=" + email + ", redacted]";
        }
    }

    /**
     * The access token is returned in the body for the frontend to hold in memory.
     * The refresh token is <b>not</b> here — it is set as an HttpOnly cookie the page's
     * JavaScript cannot read, which is what keeps a cross-site scripting bug from
     * yielding a session that outlives the tab.
     */
    public record TokenResponse(
            String accessToken,
            long expiresInSeconds,
            AccountResponse account) {
    }

    public record AccountResponse(
            String publicId,
            String email,
            String displayName,
            String role,
            long pointsBalance,
            long pointsValueMinor,
            String pointsValueFormatted,
            boolean firstOrder,
            String referredByCode) {
    }
}
