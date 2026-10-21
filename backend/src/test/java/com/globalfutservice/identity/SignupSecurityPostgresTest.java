package com.globalfutservice.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.globalfutservice.admin.AdminCustomerQueries;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.identity.PasswordResetPostgresTest.MutableClock;
import com.globalfutservice.identity.web.AuthDtos;
import com.globalfutservice.loyalty.LoyaltyService;
import com.globalfutservice.marketing.CampaignRecipientRepository;
import com.globalfutservice.marketing.CampaignRenderer;
import com.globalfutservice.marketing.CampaignRepository;
import com.globalfutservice.marketing.CampaignSender;
import com.globalfutservice.marketing.CampaignService;
import com.globalfutservice.marketing.MarketingAudienceRepository;
import com.globalfutservice.marketing.MarketingConsent;
import com.globalfutservice.marketing.MarketingConsentEventRepository;
import com.globalfutservice.marketing.MarketingPreferenceService;
import com.globalfutservice.notify.AccountMailer;
import com.globalfutservice.notify.email.AccountEmails;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.security.JwtService;
import com.globalfutservice.web.ApiExceptions;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * Sign-up against the real schema (V58): the verification link, the marketing consent and
 * how it was given, the admin Customers column, and that a customer who opted out of
 * marketing still gets every account and security email. Runs when GFS_TEST_PG_URL is set.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SignupSecurityPostgresTest {

    private static final String PASSWORD = "correct horse battery";

    private String schema;
    private JdbcTemplate jdbc;
    private LocalContainerEntityManagerFactoryBean factory;
    private TransactionTemplate tx;
    private AccountRepository accounts;
    private final MutableClock clock = new MutableClock(java.time.Instant.parse("2026-10-21T10:00:00Z"));
    private final AccountMailer mailer = mock(AccountMailer.class);
    private AuthService auth;
    private PasswordService passwords;
    private EmailVerificationService verification;
    private MarketingPreferenceService preferences;
    private CampaignService campaigns;
    private SignupSettings settings;
    private AdminCustomerQueries customers;

    @BeforeAll
    void migrate() {
        String url = System.getenv("GFS_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "GFS_TEST_PG_URL not set: no database to test against");
        schema = "signup_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        DriverManagerDataSource ds = new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                System.getenv("GFS_TEST_PG_USER"), System.getenv("GFS_TEST_PG_PASSWORD"));
        jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .initSql("SET search_path TO " + schema + ", public").load().migrate();

        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(ds);
        factory.setPackagesToScan("com.globalfutservice");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "none",
                "hibernate.default_schema", schema,
                "hibernate.jdbc.time_zone", "UTC",
                "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                "hibernate.implicit_naming_strategy",
                "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy"));
        factory.afterPropertiesSet();
        tx = new TransactionTemplate(new JpaTransactionManager(factory.getObject()));
        EntityManager shared = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        JpaRepositoryFactory repos = new JpaRepositoryFactory(shared);
        accounts = repos.getRepository(AccountRepository.class);
        RefreshTokenRepository refreshTokens = repos.getRepository(RefreshTokenRepository.class);

        AppProperties props = TestSecurity.props();
        JwtService jwt = new JwtService(props, clock);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        MarketingConsent consent = new MarketingConsent(accounts,
                repos.getRepository(MarketingConsentEventRepository.class), clock);
        verification = new EmailVerificationService(accounts,
                repos.getRepository(EmailVerificationTokenRepository.class), jwt, mailer, clock);
        auth = new AuthService(accounts, refreshTokens, encoder, jwt, mock(LoyaltyService.class),
                new SignInThrottle(props, clock), TestSecurity.humanCheckOff(), consent, verification, props, clock);
        passwords = new PasswordService(accounts, repos.getRepository(PasswordResetTokenRepository.class),
                refreshTokens, encoder, jwt, new SignInThrottle(props, clock), mailer, TestSecurity.humanCheckOff(),
                props, clock);
        preferences = new MarketingPreferenceService(accounts, consent);
        campaigns = new CampaignService(repos.getRepository(CampaignRepository.class),
                mock(CampaignRecipientRepository.class), mock(MarketingAudienceRepository.class), accounts,
                mock(CampaignSender.class), mock(CampaignRenderer.class), clock, props, consent);
        settings = new SignupSettings(repos.getRepository(SignupSettingsRepository.class), clock);
        customers = new AdminCustomerQueries(new NamedParameterJdbcTemplate(ds),
                repos.getRepository(OrderRepository.class), clock);
    }

    @AfterAll
    void drop() {
        if (factory != null) {
            factory.destroy();
        }
        if (jdbc != null) {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @BeforeEach
    void freshMailer() {
        reset(mailer);
    }

    // ------------------------------------------------------------------ helpers

    private String uniqueEmail() {
        return "u" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }

    /** Signs up and returns the raw token the verification email would carry. */
    private String signUp(String email, boolean marketing) {
        tx.executeWithoutResult(s -> auth.register(new AuthDtos.RegisterRequest(email, PASSWORD, null, null, null,
                true, marketing, "es", null), "test", "198.51.100.7"));
        return sentToken(email, AccountEmails.Lang.ES);
    }

    private String sentToken(String email, AccountEmails.Lang lang) {
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(mailer).sendVerification(eq(email), anyString(), eq(lang), token.capture(), eq(24L));
        reset(mailer);
        return token.getValue();
    }

    private AccountEntity account(String email) {
        return accounts.findByEmailNormalised(email).orElseThrow();
    }

    private void verifyWith(String token) {
        tx.executeWithoutResult(s -> verification.verify(token));
    }

    private List<Map<String, Object>> consentLog(String email) {
        return jdbc.queryForList("select opted_in, source, campaign_id, created_at from marketing_consent_event "
                + "where account_id = ? order by id", account(email).getId());
    }

    private static String sha256(String raw) throws Exception {
        return Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    }

    // --------------------------------------------------------- verification

    @Test
    @DisplayName("sign-up works at once, unverified, and mails a random link stored only as its hash for 24 hours")
    void signUpSendsLink() throws Exception {
        String email = uniqueEmail();
        String token = signUp(email, false);

        AccountEntity a = account(email);
        assertThat(a.isEmailVerified()).isFalse();
        var row = jdbc.queryForMap("select token_hash, expires_at from email_verification_token where account_id = ?",
                a.getId());
        assertThat(row.get("token_hash")).isEqualTo(sha256(token)).isNotEqualTo(token);
        assertThat(((Timestamp) row.get("expires_at")).toInstant()).isEqualTo(clock.instant().plus(Duration.ofHours(24)));
        assertThat(token).hasSizeGreaterThanOrEqualTo(40);
    }

    @Test
    @DisplayName("the link verifies the address once; used again, or after 24 hours, it is refused")
    void verifyOnceWithin24Hours() {
        String email = uniqueEmail();
        String token = signUp(email, false);
        clock.advance(Duration.ofHours(23));
        verifyWith(token);
        assertThat(account(email).isEmailVerified()).isTrue();
        assertThatThrownBy(() -> verifyWith(token)).isInstanceOf(ApiExceptions.BadRequestException.class)
                .satisfies(e -> assertThat(((ApiExceptions.BadRequestException) e).code()).isEqualTo("verify_link_invalid"));

        String late = signUp(uniqueEmail(), false);
        clock.advance(Duration.ofHours(25));
        assertThatThrownBy(() -> verifyWith(late)).isInstanceOf(ApiExceptions.BadRequestException.class);
        assertThatThrownBy(() -> verifyWith("never-sent")).isInstanceOf(ApiExceptions.BadRequestException.class);
    }

    @Test
    @DisplayName("Resend voids the older link, is limited to three an hour, and does nothing once verified")
    void resend() {
        String email = uniqueEmail();
        String first = signUp(email, false);
        long id = account(email).getId();

        tx.executeWithoutResult(s -> verification.resend(id, "fr"));
        String second = sentToken(email, AccountEmails.Lang.FR);
        assertThatThrownBy(() -> verifyWith(first)).isInstanceOf(ApiExceptions.BadRequestException.class);

        tx.executeWithoutResult(s -> verification.resend(id, "en"));
        tx.executeWithoutResult(s -> verification.resend(id, "en"));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> verification.resend(id, "en")))
                .isInstanceOf(ApiExceptions.TooManyRequestsException.class);

        reset(mailer);
        String newest = jdbc.queryForObject("select count(*) from email_verification_token where account_id = ? "
                + "and used_at is null and revoked_at is null", String.class, id);
        assertThat(newest).isEqualTo("1");
        assertThat(second).isNotEqualTo(first);

        // Verified through the latest link (stand-in: set directly), Resend sends nothing.
        jdbc.update("update account set email_verified_at = now() where id = ?", id);
        assertThat((boolean) tx.execute(s -> verification.resend(id, "en"))).isTrue();
        verify(mailer, never()).sendVerification(anyString(), anyString(), any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("Admin > Customers shows verified yes or no")
    void adminShowsVerified() {
        String verified = uniqueEmail();
        verifyWith(signUp(verified, false));
        String unverified = uniqueEmail();
        signUp(unverified, false);

        var rows = customers.search(null, AdminCustomerQueries.Filter.ALL, 0, 200, false).items();
        assertThat(rows).filteredOn(r -> r.email().equals(verified)).singleElement()
                .satisfies(r -> assertThat(r.emailVerified()).isTrue());
        assertThat(rows).filteredOn(r -> r.email().equals(unverified)).singleElement()
                .satisfies(r -> assertThat(r.emailVerified()).isFalse());
    }

    // -------------------------------------------------------------- consent

    @Test
    @DisplayName("consent is saved with when and how: sign-up, then account settings, then the unsubscribe link")
    void consentWithHow() {
        String email = uniqueEmail();
        signUp(email, true);
        AccountEntity a = account(email);
        assertThat(a.isMarketingOptIn()).isTrue();
        assertThat(a.getMarketingConsentSource()).isEqualTo(ConsentSource.SIGN_UP);
        assertThat(a.getMarketingOptInAt()).isNotNull();

        tx.executeWithoutResult(s -> preferences.set(a.getId(), false));
        assertThat(account(email).getMarketingConsentSource()).isEqualTo(ConsentSource.ACCOUNT_SETTINGS);
        tx.executeWithoutResult(s -> preferences.set(a.getId(), true));

        tx.executeWithoutResult(s -> campaigns.unsubscribe(account(email).getMarketingToken(), null));
        AccountEntity after = account(email);
        assertThat(after.isMarketingOptIn()).isFalse();
        assertThat(after.getMarketingConsentSource()).isEqualTo(ConsentSource.UNSUBSCRIBE_LINK);
        assertThat(after.getMarketingOptOutAt()).isNotNull();

        assertThat(consentLog(email)).extracting(r -> r.get("opted_in") + " " + r.get("source")).containsExactly(
                "true SIGN_UP", "false ACCOUNT_SETTINGS", "true ACCOUNT_SETTINGS", "false UNSUBSCRIBE_LINK");
    }

    @Test
    @DisplayName("a box left unticked is recorded too, as a sign-up choice")
    void untickedRecorded() {
        String email = uniqueEmail();
        signUp(email, false);
        assertThat(account(email).isMarketingOptIn()).isFalse();
        assertThat(account(email).getMarketingConsentSource()).isEqualTo(ConsentSource.SIGN_UP);
        assertThat(consentLog(email)).extracting(r -> r.get("opted_in") + " " + r.get("source"))
                .containsExactly("false SIGN_UP");
    }

    @Test
    @DisplayName("saving the same answer again from settings logs nothing new")
    void noChangeNoLog() {
        String email = uniqueEmail();
        signUp(email, false);
        tx.executeWithoutResult(s -> preferences.set(account(email).getId(), false));
        assertThat(consentLog(email)).hasSize(1);
    }

    @Test
    @DisplayName("'Pre-tick marketing consent' is off until an admin turns it on")
    void prefillSetting() {
        assertThat(settings.prefillMarketingConsent()).isFalse();
        tx.executeWithoutResult(s -> settings.setPrefillMarketingConsent(true, null));
        assertThat(settings.prefillMarketingConsent()).isTrue();
        tx.executeWithoutResult(s -> settings.setPrefillMarketingConsent(false, null));
        assertThat(settings.prefillMarketingConsent()).isFalse();
    }

    @Test
    @DisplayName("opted out of marketing, a customer still gets verification, reset and password-changed emails")
    void optedOutStillGetsAccountEmails() {
        String email = uniqueEmail();
        signUp(email, false);
        tx.executeWithoutResult(s -> campaigns.unsubscribe(account(email).getMarketingToken(), null));
        long id = account(email).getId();
        assertThat(account(email).isMarketingOptIn()).isFalse();

        tx.executeWithoutResult(s -> verification.resend(id, "en"));
        verify(mailer).sendVerification(eq(email), anyString(), any(), anyString(), anyLong());

        tx.executeWithoutResult(s -> passwords.requestReset(email, "en", null, "198.51.100.8"));
        ArgumentCaptor<String> reset = ArgumentCaptor.forClass(String.class);
        verify(mailer).sendResetLink(eq(email), anyString(), any(), reset.capture(), anyLong());

        tx.executeWithoutResult(s -> passwords.resetPassword(reset.getValue(), "a brand new passphrase", "en"));
        verify(mailer).sendPasswordChanged(eq(email), anyString(), any(), any());
    }
}
