package com.globalfutservice.config;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * The social sign-in registrations, built here rather than bound from configuration.
 *
 * <p><b>Why this is not in {@code application.yml}.</b> Declaring the registrations under
 * {@code spring.security.oauth2.client.registration} with empty defaults looks like it
 * makes them optional. It does the opposite. Spring Boot binds every declared
 * registration and then validates it, and {@code OAuth2ClientProperties.validate()}
 * throws {@code "Client id of registration 'google' must not be empty."} — so an install
 * that had simply not set the secrets did not start at all. The whole point of the
 * {@code @Autowired(required = false)} repository in {@link SecurityConfig} is that a
 * deployment without credentials should run happily with no social buttons, and that
 * branch was unreachable while Spring's own validator ran first.
 *
 * <p>Built here, absence is expressible: if neither provider has both halves of its
 * credentials, no bean is defined, Spring Security's OAuth login is switched off, and
 * {@code /api/v1/auth/providers} reports an empty list so the storefront renders a plain
 * password form.
 *
 * <p><b>Both halves, or neither.</b> A provider with an id and no secret is the one
 * genuinely broken state — the button appears and the flow dies at the token exchange,
 * after the customer has already left the site. That combination is treated as
 * unconfigured and logged as a warning, because it is almost always half-finished setup
 * rather than an intention.
 */
@Configuration(proxyBeanMethods = false)
public class OAuthClientsConfig {

    private static final Logger log = LoggerFactory.getLogger(OAuthClientsConfig.class);

    /** The redirect Spring Security fills in per host; must match the provider console. */
    private static final String REDIRECT_URI = "{baseUrl}/login/oauth2/code/{registrationId}";

    static boolean isConfigured(Environment env, String provider) {
        return hasText(env.getProperty("GFS_" + provider + "_CLIENT_ID"))
                && hasText(env.getProperty("GFS_" + provider + "_CLIENT_SECRET"));
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /** Present only when at least one provider is fully configured. */
    static class AnyProviderConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            return isConfigured(env, "GOOGLE") || isConfigured(env, "DISCORD");
        }
    }

    /**
     * Says out loud, at startup, that social sign-in is off and exactly why.
     *
     * <p>Silence was the actual defect. With no credentials the conditional bean above
     * is simply never created — no warning, no error, nothing in the log at all — and
     * the only visible symptom is that two buttons stop appearing on a page a developer
     * may not be looking at. That is indistinguishable from "the feature was never
     * built", and it cost several rounds of diagnosis to tell apart more than once.
     *
     * <p>So this bean is unconditional and names the missing variables. It is the same
     * courtesy {@link #warnHalfConfigured} already extended to a half-finished provider,
     * which was the narrower case; the common one — nothing set, because the process was
     * started by a runner that does not load {@code .env} — said nothing.
     *
     * <p>Registered as an {@code ApplicationRunner} rather than a {@code @PostConstruct}
     * so it prints after the banner and the rest of startup, where someone scanning the
     * log will actually see it.
     */
    @Bean
    ApplicationRunner socialSignInStatusReport(Environment env) {
        return args -> {
            boolean google = isConfigured(env, "GOOGLE");
            boolean discord = isConfigured(env, "DISCORD");
            if (google || discord) return;   // the enabled path already logs its own line

            List<String> missing = new ArrayList<>();
            for (String provider : List.of("GOOGLE", "DISCORD")) {
                for (String half : List.of("CLIENT_ID", "CLIENT_SECRET")) {
                    String key = "GFS_" + provider + "_" + half;
                    if (!hasText(env.getProperty(key))) missing.add(key);
                }
            }
            log.warn("Social sign-in is OFF — no provider is configured, so "
                    + "/api/v1/auth/providers returns [] and the Google and Discord "
                    + "buttons will not render. Not set: {}. These are read from the "
                    + "environment, or from .env via spring.config.import; a runner "
                    + "started without either sees none of them.", missing);
        };
    }

    @Bean
    @Conditional(AnyProviderConfigured.class)
    public ClientRegistrationRepository clientRegistrationRepository(
            @Value("${GFS_GOOGLE_CLIENT_ID:}") String googleId,
            @Value("${GFS_GOOGLE_CLIENT_SECRET:}") String googleSecret,
            @Value("${GFS_DISCORD_CLIENT_ID:}") String discordId,
            @Value("${GFS_DISCORD_CLIENT_SECRET:}") String discordSecret) {

        List<ClientRegistration> registrations =
                buildRegistrations(googleId, googleSecret, discordId, discordSecret);

        /*
         * Unreachable, and asserted anyway. `InMemoryClientRegistrationRepository`
         * rejects an empty list, so this method is only safe because the condition
         * above guarantees at least one fully-configured provider. Both derive from
         * `isConfigured`, which is what keeps them honest — if that ever stops being
         * true, this says so instead of throwing "registrations cannot be empty" from
         * three frames down inside Spring.
         */
        if (registrations.isEmpty()) {
            throw new IllegalStateException(
                    "clientRegistrationRepository was built with no providers. "
                    + "AnyProviderConfigured and buildRegistrations have drifted apart.");
        }

        log.info("Social sign-in enabled for: {}",
                registrations.stream().map(ClientRegistration::getRegistrationId).toList());

        return new InMemoryClientRegistrationRepository(registrations);
    }

    /**
     * The registrations for whichever providers have <em>both</em> halves of their
     * credentials. Empty is a legitimate answer here — it is the caller's job to decide
     * what that means.
     */
    static List<ClientRegistration> buildRegistrations(
            String googleId, String googleSecret, String discordId, String discordSecret) {

        List<ClientRegistration> registrations = new ArrayList<>();

        if (hasText(googleId) && hasText(googleSecret)) {
            /*
             * Google's endpoints, scopes and JWK set come from Spring Security's own
             * constant rather than being retyped. They are not ours to get wrong, and a
             * stale token URI here would fail only at the exchange.
             */
            registrations.add(CommonOAuth2Provider.GOOGLE
                    .getBuilder("google")
                    .clientId(googleId)
                    .clientSecret(googleSecret)
                    .redirectUri(REDIRECT_URI)
                    .build());
        } else if (hasText(googleId) || hasText(googleSecret)) {
            warnHalfConfigured("Google", "GFS_GOOGLE_CLIENT_ID", "GFS_GOOGLE_CLIENT_SECRET");
        }

        if (hasText(discordId) && hasText(discordSecret)) {
            /*
             * Discord is not one of Spring's built-ins, so its three endpoints are
             * written out. `id` is the snowflake — the only Discord field that never
             * changes. Keying an account on the username would break the moment a
             * customer edited it.
             */
            registrations.add(ClientRegistration.withRegistrationId("discord")
                    .clientId(discordId)
                    .clientSecret(discordSecret)
                    .clientName("Discord")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri(REDIRECT_URI)
                    .scope("identify", "email")
                    .authorizationUri("https://discord.com/api/oauth2/authorize")
                    .tokenUri("https://discord.com/api/oauth2/token")
                    .userInfoUri("https://discord.com/api/users/@me")
                    .userNameAttributeName("id")
                    .build());
        } else if (hasText(discordId) || hasText(discordSecret)) {
            warnHalfConfigured("Discord", "GFS_DISCORD_CLIENT_ID", "GFS_DISCORD_CLIENT_SECRET");
        }

        return registrations;
    }

    private static void warnHalfConfigured(String name, String idVar, String secretVar) {
        log.warn("{} sign-in is half-configured and has been left OFF: {} and {} must both "
                + "be set. A button whose token exchange fails is worse than no button.",
                name, idVar, secretVar);
    }
}
