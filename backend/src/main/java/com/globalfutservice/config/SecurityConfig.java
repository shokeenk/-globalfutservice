package com.globalfutservice.config;

import com.globalfutservice.security.JwtAuthenticationFilter;
import com.globalfutservice.security.RateLimitFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import com.globalfutservice.identity.web.OAuthSuccessHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * The single place that decides what is public and what is not.
 *
 * <p>Worth reading top to bottom before changing anything: an accidental
 * {@code permitAll} on a prefix is how order data becomes public, and it is invisible in
 * a code review that only looks at controllers.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final AppProperties props;

    public SecurityConfig(AppProperties props) {
        this.props = props;
    }

    /**
     * BCrypt at strength 12.
     *
     * <p>Strength is a deliberate trade: 12 costs roughly a quarter of a second per
     * verification on modern hardware, which is unnoticeable to a person signing in and
     * ruinous to anyone working through a leaked hash table. Argon2id would be stronger
     * still and is the upgrade path if the account table ever gets large enough to be
     * worth stealing.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtFilter,
            RateLimitFilter rateLimitFilter,
            // Absent unless a provider has a client id, which is what makes the whole
            // OAuth flow opt-in rather than a broken button on an unconfigured install.
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            ClientRegistrationRepository clientRegistrations,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            OAuthSuccessHandler oauthSuccessHandler) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            // CSRF protection is disabled because there is no cookie the browser will
            // attach to a state-changing request on its own: the API authenticates from
            // an Authorization header, and the one cookie that exists — the refresh
            // token — is SameSite=Strict, which browsers refuse to send on any
            // cross-site request. Reintroduce CSRF tokens the moment any endpoint starts
            // authenticating from a cookie.
            .csrf(csrf -> csrf.disable())

            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // 401 with an empty body rather than a redirect to a login page that does not
            // exist on an API.
            .exceptionHandling(e -> e
                    .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

            .headers(headers -> headers
                    .frameOptions(f -> f.deny())
                    .contentTypeOptions(Customizer.withDefaults())
                    .referrerPolicy(r -> r.policy(
                            ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                    .httpStrictTransportSecurity(hsts -> hsts
                            .includeSubDomains(true)
                            .maxAgeInSeconds(63_072_000)
                            .preload(props.security().requireHttps()))
                    .contentSecurityPolicy(csp -> csp.policyDirectives(String.join("; ",
                            // This service returns JSON. Nothing should ever be executed,
                            // framed or embedded from a response of ours.
                            "default-src 'none'",
                            "frame-ancestors 'none'",
                            "base-uri 'none'",
                            "form-action 'none'")))
                    .permissionsPolicy(p -> p.policy(
                            "camera=(), microphone=(), geolocation=(), payment=(), usb=()")))

            /*
              OAuth2 login, when a provider is configured.

              `ClientRegistrationRepository` is only present once at least one
              registration has a client id, so the whole flow is absent from the filter
              chain on a deployment that has not set the secrets — rather than present
              and failing at the redirect. `oauth2Login` is a sign-in mechanism only; the
              session it produces is the ordinary refresh cookie, issued by the handler.
            */
            .oauth2Login(oauth -> {
                if (clientRegistrations == null) {
                    oauth.disable();
                    return;
                }
                oauth.successHandler(oauthSuccessHandler);
                oauth.failureHandler((request, response, exception) -> {
                    var origins = props.security().corsAllowedOrigins();
                    String base = (origins == null || origins.isEmpty()) ? "" : origins.get(0);
                    response.sendRedirect(base + "/auth/callback?oauth_error=PROVIDER_ERROR");
                });
            })

            .authorizeHttpRequests(auth -> auth
                    // --- public storefront -------------------------------------------
                    .requestMatchers(HttpMethod.GET, "/api/v1/catalog/**").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/quotes").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/orders").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/orders/track").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/support/tickets").permitAll()

                    /*
                      Paying without a gateway, and saying you have.

                      Both are open for the same reason POST /orders is: guest checkout
                      exists, and most people paying have no account. Withholding them
                      from guests does not protect anything -- it just means the customer
                      cannot pay.

                      What each actually exposes:

                        * `methods` returns the addresses that are already printed on the
                          checkout page and encoded in the QR images this repo serves. It
                          is public information by construction.

                        * `claims/{ref}` is authenticated inside the handler, by reference
                          plus the email on the order -- the same pair /orders/track
                          requires. The reference alone is not a credential, and the
                          handler refuses anything that is not AWAITING_PAYMENT. Worst
                          case for a guessed pair is a claim an operator then fails to
                          find money for and rejects.

                      Enumerated rather than opening /payments/**, following the rule the
                      coaching block above sets: the same prefix already carries the
                      Razorpay webhook, and it will carry more later.
                    */
                    .requestMatchers(HttpMethod.GET, "/api/v1/payments/methods").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/payments/claims/*").permitAll()
                    .requestMatchers("/api/v1/auth/**").permitAll()

                    // The OAuth redirect dance. Both legs must be reachable to a browser
                    // that is not signed in yet — that is the entire point of them — and
                    // neither carries anything but the provider's own state and code.
                    .requestMatchers("/oauth2/authorization/**").permitAll()
                    .requestMatchers("/login/oauth2/code/**").permitAll()

                    // The coaching shop window: who teaches, when they are free, and the
                    // booking rules. Enumerated one path at a time rather than opening
                    // /coaching/** — the same prefix carries /me, /credits and the booking
                    // endpoints, and a wildcard here would hand a stranger somebody else's
                    // session list.
                    .requestMatchers(HttpMethod.GET, "/api/v1/coaching/coaches").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/coaching/coaches/*/slots").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/coaching/policy").permitAll()

                    // Signed by Razorpay, verified in the handler. Authentication here
                    // would be meaningless — the caller is a server, not a person.
                    .requestMatchers(HttpMethod.POST, "/api/v1/payments/webhook/**").permitAll()

                    // --- operations ---------------------------------------------------
                    .requestMatchers("/api/v1/admin/**").hasRole("OPERATOR")

                    // --- health and docs ----------------------------------------------
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .requestMatchers("/actuator/**").hasRole("ADMIN")
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()

                    // Everything else — account pages, order history, the credential
                    // submission endpoint — requires a signed-in caller.
                    .anyRequest().authenticated())

            .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Exact origins only.
     *
     * <p>A wildcard origin cannot be combined with credentials, and the "reflect whatever
     * Origin was sent" pattern that people reach for instead is equivalent to having no
     * same-origin policy at all.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(props.security().corsAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With",
                "Idempotency-Key"));
        config.setExposedHeaders(List.of("Retry-After"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
