package com.globalfutservice.identity.web;

import com.globalfutservice.identity.EmailVerificationService;
import com.globalfutservice.identity.SignupSettings;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.security.HumanCheck;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What the sign-up form starts with, and email verification. */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class SignupController {

    private final SignupSettings settings;
    private final HumanCheck humanCheck;
    private final EmailVerificationService verification;

    public SignupController(SignupSettings settings, HumanCheck humanCheck, EmailVerificationService verification) {
        this.settings = settings;
        this.humanCheck = humanCheck;
        this.verification = verification;
    }

    public record VerifiedResponse(boolean verified) {
    }

    @GetMapping("/signup-config")
    @Operation(summary = "Whether the marketing box starts ticked, and the Turnstile site key")
    public ResponseEntity<AuthDtos.SignupConfigResponse> config() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AuthDtos.SignupConfigResponse(settings.prefillMarketingConsent(), humanCheck.siteKey()));
    }

    @PostMapping("/email/confirm")
    @Operation(summary = "Verify an email address from the link in the email")
    public ResponseEntity<VerifiedResponse> verify(@Valid @RequestBody AuthDtos.VerifyEmailRequest request) {
        verification.verify(request.token());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(new VerifiedResponse(true));
    }

    @PostMapping("/email/resend")
    @Operation(summary = "Send the signed-in account another verification link")
    public ResponseEntity<VerifiedResponse> resend(
            @CurrentAccount AccountPrincipal principal,
            @Valid @RequestBody(required = false) AuthDtos.ResendVerificationRequest request) {
        if (principal == null) {
            throw new ApiExceptions.ForbiddenException("Please sign in.");
        }
        boolean already = verification.resend(principal.id(), request == null ? null : request.language());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(new VerifiedResponse(already));
    }
}
