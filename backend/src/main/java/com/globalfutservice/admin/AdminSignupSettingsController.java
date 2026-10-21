package com.globalfutservice.admin;

import com.globalfutservice.identity.SignupSettings;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Pre-tick marketing consent", for admins. Off by default: pre-ticked consent is not valid
 * for EU customers, and turning it on is the client's decision.
 */
@RestController
@RequestMapping("/api/v1/admin/settings/signup")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin")
public class AdminSignupSettingsController {

    private final SignupSettings settings;

    public AdminSignupSettingsController(SignupSettings settings) {
        this.settings = settings;
    }

    public record SignupSettingsView(boolean prefillMarketingConsent) {
    }

    @GetMapping
    @Operation(summary = "Sign-up settings")
    public ResponseEntity<SignupSettingsView> get() {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new SignupSettingsView(settings.prefillMarketingConsent()));
    }

    @PutMapping
    @Operation(summary = "Turn Pre-tick marketing consent on or off")
    public ResponseEntity<SignupSettingsView> put(@CurrentAccount AccountPrincipal principal,
                                                  @RequestBody SignupSettingsView request) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new SignupSettingsView(settings.setPrefillMarketingConsent(
                        request.prefillMarketingConsent(), principal.id())));
    }
}
