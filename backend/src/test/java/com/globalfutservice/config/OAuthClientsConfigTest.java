package com.globalfutservice.config;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When social sign-in is on, off, and half-done.
 *
 * <p>The "off" case is why this file exists. The four credentials used to be declared in
 * {@code application.yml} with empty defaults, which reads as optional and is not: Spring
 * Boot validates every declared registration and rejects an empty client id, so an
 * install that had simply not set the secrets died at startup with
 * {@code "Client id of registration 'google' must not be empty."} Nothing caught it,
 * because every test and every run had the credentials present. The unconfigured case is
 * the one nobody exercises by accident, so it is the one that has to be asserted.
 */
class OAuthClientsConfigTest {

    private static final String G_ID = "google-id.apps.googleusercontent.com";
    private static final String G_SECRET = "google-secret";
    private static final String D_ID = "1234567890";
    private static final String D_SECRET = "discord-secret";

    private final OAuthClientsConfig config = new OAuthClientsConfig();

    private static boolean conditionMatches(String gid, String gsecret, String did, String dsecret) {
        MockEnvironment env = new MockEnvironment();
        if (gid != null) env.setProperty("GFS_GOOGLE_CLIENT_ID", gid);
        if (gsecret != null) env.setProperty("GFS_GOOGLE_CLIENT_SECRET", gsecret);
        if (did != null) env.setProperty("GFS_DISCORD_CLIENT_ID", did);
        if (dsecret != null) env.setProperty("GFS_DISCORD_CLIENT_SECRET", dsecret);
        return OAuthClientsConfig.isConfigured(env, "GOOGLE")
                || OAuthClientsConfig.isConfigured(env, "DISCORD");
    }

    /*
     * Goes through the pure builder rather than the @Bean method. The bean cannot
     * legally produce an empty repository — Spring's InMemoryClientRegistrationRepository
     * rejects an empty list — so the half-configured cases below are only expressible
     * against the function that is allowed to return nothing.
     */
    private List<String> idsFor(String gid, String gsecret, String did, String dsecret) {
        return OAuthClientsConfig.buildRegistrations(
                        gid == null ? "" : gid, gsecret == null ? "" : gsecret,
                        did == null ? "" : did, dsecret == null ? "" : dsecret)
                .stream()
                .map(ClientRegistration::getRegistrationId)
                .toList();
    }

    @Nested
    @DisplayName("nothing configured")
    class Unconfigured {

        @Test
        @DisplayName("no bean is defined, so the app starts with social sign-in absent")
        void noBean() {
            assertThat(conditionMatches(null, null, null, null)).isFalse();
        }

        @Test
        @DisplayName("empty strings count as absent, not as a credential")
        void emptyStringsAreAbsent() {
            assertThat(conditionMatches("", "", "", "")).isFalse();
        }

        @Test
        @DisplayName("whitespace is not a credential either")
        void blankIsAbsent() {
            assertThat(conditionMatches("  ", "  ", "  ", "  ")).isFalse();
        }
    }

    @Nested
    @DisplayName("half configured")
    class HalfConfigured {

        @Test
        @DisplayName("an id with no secret leaves that provider off")
        void idWithoutSecret() {
            assertThat(conditionMatches(G_ID, null, null, null)).isFalse();
            assertThat(idsFor(G_ID, null, null, null)).isEmpty();
        }

        @Test
        @DisplayName("a secret with no id leaves that provider off")
        void secretWithoutId() {
            assertThat(idsFor(null, G_SECRET, null, null)).isEmpty();
        }

        @Test
        @DisplayName("one broken provider does not take the working one down with it")
        void oneBrokenDoesNotBreakTheOther() {
            assertThat(idsFor(G_ID, null, D_ID, D_SECRET)).containsExactly("discord");
        }
    }

    @Nested
    @DisplayName("configured")
    class Configured {

        @Test
        @DisplayName("google alone")
        void googleOnly() {
            assertThat(conditionMatches(G_ID, G_SECRET, null, null)).isTrue();
            assertThat(idsFor(G_ID, G_SECRET, null, null)).containsExactly("google");
        }

        @Test
        @DisplayName("discord alone")
        void discordOnly() {
            assertThat(idsFor(null, null, D_ID, D_SECRET)).containsExactly("discord");
        }

        @Test
        @DisplayName("both")
        void both() {
            assertThat(idsFor(G_ID, G_SECRET, D_ID, D_SECRET))
                    .containsExactlyInAnyOrder("google", "discord");
        }

        @Test
        @DisplayName("discord keys on the snowflake id, which is the only stable field")
        void discordUsesSnowflake() {
            ClientRegistration discord = registration("discord");
            assertThat(discord.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName())
                    .isEqualTo("id");
            assertThat(discord.getScopes()).containsExactlyInAnyOrder("identify", "email");
        }

        @Test
        @DisplayName("both redirect URIs are the template Spring fills in per host")
        void redirectUriIsTheTemplate() {
            // Registered literally in each provider's console; a mismatch here fails at
            // the provider rather than in our logs, which is why it is asserted.
            assertThat(registration("google").getRedirectUri())
                    .isEqualTo("{baseUrl}/login/oauth2/code/{registrationId}");
            assertThat(registration("discord").getRedirectUri())
                    .isEqualTo("{baseUrl}/login/oauth2/code/{registrationId}");
        }

        private ClientRegistration registration(String id) {
            // Through the bean here on purpose: this is the fully-configured path, so
            // it also proves the repository itself builds and can be looked up.
            return config.clientRegistrationRepository(G_ID, G_SECRET, D_ID, D_SECRET)
                    .findByRegistrationId(id);
        }
    }
}
