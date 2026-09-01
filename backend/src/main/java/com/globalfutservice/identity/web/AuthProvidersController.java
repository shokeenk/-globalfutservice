package com.globalfutservice.identity.web;

import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Which social sign-ins this deployment actually has.
 *
 * <p>The storefront asks rather than assuming. A "Continue with Discord" button on an
 * install where no Discord secret was ever set is worse than no button: it looks like
 * a working option, and it fails after the customer has already left the site for a
 * provider that rejects the request. Served here, the button only exists where the
 * flow behind it does.
 *
 * <p>Nothing secret is returned — only which registration ids exist. The client id is
 * public by design in the authorization-code flow, but there is no reason to hand it
 * out from an endpoint that does not need to.
 */
@RestController
@RequestMapping("/api/v1/auth/providers")
@Tag(name = "Auth")
public class AuthProvidersController {

    private final ClientRegistrationRepository registrations;

    public AuthProvidersController(
            @Autowired(required = false) ClientRegistrationRepository registrations) {
        this.registrations = registrations;
    }

    public record ProvidersResponse(List<String> providers) {
    }

    @GetMapping
    @Operation(summary = "Social sign-in providers configured on this deployment")
    public ResponseEntity<ProvidersResponse> providers() {
        List<String> available = new ArrayList<>();

        /*
         * Only the in-memory repository can be enumerated. That is the one Spring builds
         * from `application.yml`, which is how this application is configured — and if a
         * deployment ever swaps in another implementation, reporting nothing is the right
         * failure: the storefront hides the buttons rather than advertising a flow this
         * endpoint could not confirm.
         */
        if (registrations instanceof InMemoryClientRegistrationRepository inMemory) {
            inMemory.forEach(r -> available.add(r.getRegistrationId()));
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePublic())
                .body(new ProvidersResponse(available));
    }
}
