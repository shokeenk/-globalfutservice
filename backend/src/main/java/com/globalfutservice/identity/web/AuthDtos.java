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
            boolean acceptedTerms,

            /**
             * Agreement to receive promotional email. Optional, and separate from
             * {@code acceptedTerms} on purpose.
             *
             * <p>Not annotated {@code @AssertTrue}: accepting the terms is a condition of
             * having an account, whereas marketing is a thing somebody chooses, and
             * bundling the two would make every registration look like consent. Absent
             * from the body means false, which is the answer that does not mail people
             * who never said yes.
             */
            boolean marketingOptIn,

            /** The storefront's language, for the verification email. */
            @Size(max = 8) String language,

            /** Cloudflare Turnstile's token, checked on the server. See HumanCheck. */
            @Size(max = 4096) String humanToken) {

        @Override
        public String toString() {
            return "RegisterRequest[email=" + email + ", redacted]";
        }
    }

    /**
     * @param rememberMe ticked: the long-lived refresh cookie. Unticked: a session cookie
     *                   that goes when the browser closes. Absent: ticked, as every
     *                   sign-in was before the box existed.
     */
    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password,
            Boolean rememberMe,
            /** Asked for after three failed sign-ins: see AuthService. */
            @Size(max = 4096) String humanToken) {

        @Override
        public String toString() {
            return "LoginRequest[email=" + email + ", redacted]";
        }
    }

    /**
     * "Forgot password?". {@code language} is the storefront's, en, es or fr, and picks the
     * email's; anything else gets English.
     */
    public record ForgotPasswordRequest(
            @Email(message = "That does not look like an email address")
            @NotBlank(message = "Email is required")
            @Size(max = 255)
            String email,
            @Size(max = 8) String language,
            @Size(max = 4096) String humanToken) {
    }

    /** The same length rule as sign-up: see {@link RegisterRequest#password()}. */
    public record ResetPasswordRequest(
            @NotBlank @Size(max = 512) String token,
            @NotBlank(message = "Password is required")
            @Size(min = 12, max = 128, message = "Use at least 12 characters — a short phrase works well")
            String password,
            @Size(max = 8) String language) {

        @Override
        public String toString() {
            return "ResetPasswordRequest[redacted]";
        }
    }

    /** Signed in, from Account settings. The new password follows the sign-up rule. */
    public record ChangePasswordRequest(
            @NotBlank(message = "Enter your current password") @Size(max = 128) String currentPassword,
            @NotBlank(message = "Password is required")
            @Size(min = 12, max = 128, message = "Use at least 12 characters — a short phrase works well")
            String newPassword,
            @Size(max = 8) String language) {

        @Override
        public String toString() {
            return "ChangePasswordRequest[redacted]";
        }
    }

    /** "Set a password" for an account made with Google or Discord: the reset link goes to its email. */
    public record SetPasswordLinkRequest(@Size(max = 8) String language) {
    }

    /** The answer to a forgot-password request, identical whether or not the account exists. */
    public record MessageResponse(String message) {
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
            String referredByCode,
            /** False for an account made with Google or Discord that has never set one. */
            boolean hasPassword,
            /** False for an email and password account whose address is not verified yet. */
            boolean emailVerified) {
    }

    /** A verification link opened: its token. */
    public record VerifyEmailRequest(@NotBlank @Size(max = 512) String token) {
    }

    /** The Resend button: the language for the email. */
    public record ResendVerificationRequest(@Size(max = 8) String language) {
    }

    /**
     * What the sign-up and sign-in forms need before they render: whether the marketing box
     * starts ticked, and the Turnstile site key -- null when the human check is off.
     */
    public record SignupConfigResponse(boolean prefillMarketingConsent, String turnstileSiteKey) {
    }
}
