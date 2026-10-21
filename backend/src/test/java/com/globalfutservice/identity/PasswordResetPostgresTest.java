package com.globalfutservice.identity;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.identity.web.AuthDtos;
import com.globalfutservice.loyalty.LoyaltyService;
import com.globalfutservice.notify.AccountMailer;
import com.globalfutservice.notify.email.AccountEmails;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import static org.mockito.Mockito.when;

/**
 * Password recovery, change and "Remember me" against the real schema: V57's table, the
 * one-statement spend that makes a link single use, and the refresh tokens a new password
 * revokes. Runs when GFS_TEST_PG_URL is set, in a throwaway schema.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PasswordResetPostgresTest {

    private static final String OLD_PASSWORD = "correct horse battery";
    private static final String NEW_PASSWORD = "a brand new passphrase";

    private String schema;
    private JdbcTemplate jdbc;
    private LocalContainerEntityManagerFactoryBean factory;
    private TransactionTemplate tx;
    private AccountRepository accounts;
    private RefreshTokenRepository refreshTokens;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-21T10:00:00Z"));
    private final AccountMailer mailer = mock(AccountMailer.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private AppProperties props;
    private PasswordService passwords;
    private AuthService auth;

    @BeforeAll
    void migrate() {
        String url = System.getenv("GFS_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "GFS_TEST_PG_URL not set: no database to test against");
        schema = "pw_reset_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
        JpaRepositoryFactory repositories = new JpaRepositoryFactory(shared);
        accounts = repositories.getRepository(AccountRepository.class);
        refreshTokens = repositories.getRepository(RefreshTokenRepository.class);
        PasswordResetTokenRepository resetTokens = repositories.getRepository(PasswordResetTokenRepository.class);

        props = TestSecurity.props();
        JwtService jwt = new JwtService(props, clock);
        // A fresh throttle per service is enough here: the limits have their own tests.
        passwords = new PasswordService(accounts, resetTokens, refreshTokens, encoder, jwt,
                new SignInThrottle(props, clock), mailer, TestSecurity.humanCheckOff(), props, clock);
        auth = new AuthService(accounts, refreshTokens, encoder, jwt, mock(LoyaltyService.class),
                new SignInThrottle(props, clock), TestSecurity.humanCheckOff(), mock(com.globalfutservice.marketing.MarketingConsent.class),
                mock(EmailVerificationService.class), props, clock);
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

    private String account(String email, String password) {
        String publicId = "acc_" + UUID.randomUUID().toString().substring(0, 8);
        // No password means made with Google or Discord, which the schema requires to be recorded.
        jdbc.update("insert into account (public_id, email, email_normalised, password_hash, oauth_provider, "
                        + "oauth_subject) values (?, ?, ?, ?, ?, ?)",
                publicId, email, email.toLowerCase(), password == null ? null : encoder.encode(password),
                password == null ? "google" : null, password == null ? "sub-" + publicId : null);
        return email;
    }

    private long idOf(String email) {
        return jdbc.queryForObject("select id from account where email_normalised = ?", Long.class, email.toLowerCase());
    }

    private String uniqueEmail() {
        return "u" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }

    /** Asks for a link and returns the raw token the email would carry. */
    private String requestLink(String email) {
        tx.executeWithoutResult(s -> passwords.requestReset(email, "en", null, "203.0.113.9"));
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(mailer).sendResetLink(eq(email), anyString(), eq(AccountEmails.Lang.EN), token.capture(), eq(30L));
        reset(mailer);
        return token.getValue();
    }

    private void resetWith(String token, String password) {
        tx.executeWithoutResult(s -> passwords.resetPassword(token, password, "en"));
    }

    private AuthService.Session login(String email, String password, Boolean rememberMe) {
        return tx.execute(s -> auth.login(new AuthDtos.LoginRequest(email, password, rememberMe, null), "test", "198.51.100.7"));
    }

    private AuthService.Session refresh(String raw) {
        return tx.execute(s -> auth.refresh(raw, "test", "198.51.100.7"));
    }

    private static String sha256(String raw) throws Exception {
        return Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    }

    // -------------------------------------------------------------------- tests

    @Test
    @DisplayName("a reset link is random, stored only as its hash, and expires 30 minutes after it is sent")
    void storedHashed() throws Exception {
        String email = account(uniqueEmail(), OLD_PASSWORD);
        String token = requestLink(email);
        String other = requestLink(email);

        assertThat(token).hasSizeGreaterThanOrEqualTo(40).isNotEqualTo(other);
        var row = jdbc.queryForMap("select token_hash, expires_at from password_reset_token "
                + "where account_id = ? order by id limit 1", idOf(email));
        assertThat(row.get("token_hash")).isEqualTo(sha256(token)).isNotEqualTo(token);
        assertThat(((java.sql.Timestamp) row.get("expires_at")).toInstant())
                .isEqualTo(clock.instant().plus(Duration.ofMinutes(30)));
        assertThat(jdbc.queryForObject("select count(*) from password_reset_token where token_hash = ?",
                Integer.class, token)).isZero();
    }

    @Test
    @DisplayName("an unknown address gets the same treatment from the caller's side, and no link or email")
    void unknownAddress() {
        tx.executeWithoutResult(s -> passwords.requestReset("nobody-" + UUID.randomUUID() + "@example.test",
                "fr", null, "203.0.113.10"));
        verify(mailer, never()).sendResetLink(anyString(), anyString(), any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("a link still works at 29 minutes and no longer at 31")
    void expiry() {
        String email = account(uniqueEmail(), OLD_PASSWORD);
        String late = requestLink(email);
        clock.advance(Duration.ofMinutes(31));
        assertThatThrownBy(() -> resetWith(late, NEW_PASSWORD))
                .isInstanceOf(ApiExceptions.BadRequestException.class)
                .extracting(e -> ((ApiExceptions.BadRequestException) e).code()).isEqualTo("reset_link_invalid");
        assertThat(encoder.matches(OLD_PASSWORD, accounts.findById(idOf(email)).orElseThrow().getPasswordHash()))
                .isTrue();

        String onTime = requestLink(email);
        clock.advance(Duration.ofMinutes(29));
        resetWith(onTime, NEW_PASSWORD);
        assertThat(encoder.matches(NEW_PASSWORD, accounts.findById(idOf(email)).orElseThrow().getPasswordHash()))
                .isTrue();
    }

    @Test
    @DisplayName("a link works once, and asking again voids the one before")
    void singleUse() {
        String email = account(uniqueEmail(), OLD_PASSWORD);
        String first = requestLink(email);
        String second = requestLink(email);

        assertThatThrownBy(() -> resetWith(first, NEW_PASSWORD)).isInstanceOf(ApiExceptions.BadRequestException.class);
        resetWith(second, NEW_PASSWORD);
        assertThatThrownBy(() -> resetWith(second, "yet another passphrase"))
                .isInstanceOf(ApiExceptions.BadRequestException.class);
        assertThatThrownBy(() -> resetWith("not-a-token-we-ever-sent", "yet another passphrase"))
                .isInstanceOf(ApiExceptions.BadRequestException.class);
        assertThat(encoder.matches(NEW_PASSWORD, accounts.findById(idOf(email)).orElseThrow().getPasswordHash()))
                .isTrue();
    }

    @Test
    @DisplayName("a reset signs out every session of the account and sends the security email")
    void resetSignsOutEverywhere() {
        String email = account(uniqueEmail(), OLD_PASSWORD);
        AuthService.Session phone = login(email, OLD_PASSWORD, true);
        AuthService.Session laptop = login(email, OLD_PASSWORD, false);
        String other = account(uniqueEmail(), OLD_PASSWORD);
        AuthService.Session someoneElse = login(other, OLD_PASSWORD, true);

        resetWith(requestLink(email), NEW_PASSWORD);

        assertThatThrownBy(() -> refresh(phone.refreshToken())).isInstanceOf(ApiExceptions.ForbiddenException.class);
        assertThatThrownBy(() -> refresh(laptop.refreshToken())).isInstanceOf(ApiExceptions.ForbiddenException.class);
        assertThat(refresh(someoneElse.refreshToken())).isNotNull();
        verify(mailer).sendPasswordChanged(eq(email), anyString(), eq(AccountEmails.Lang.EN), any());

        assertThatThrownBy(() -> login(email, OLD_PASSWORD, true)).isInstanceOf(ApiExceptions.BadRequestException.class);
        assertThat(login(email, NEW_PASSWORD, true)).isNotNull();
    }

    @Test
    @DisplayName("changing the password keeps this device signed in and signs out the others")
    void changeSignsOutOthers() {
        String email = account(uniqueEmail(), OLD_PASSWORD);
        AuthService.Session here = login(email, OLD_PASSWORD, true);
        AuthService.Session elsewhere = login(email, OLD_PASSWORD, true);

        tx.executeWithoutResult(s -> passwords.changePassword(idOf(email), OLD_PASSWORD, NEW_PASSWORD, "es",
                here.refreshToken(), "198.51.100.7"));

        assertThat(refresh(here.refreshToken())).isNotNull();
        assertThatThrownBy(() -> refresh(elsewhere.refreshToken())).isInstanceOf(ApiExceptions.ForbiddenException.class);
        verify(mailer).sendPasswordChanged(eq(email), anyString(), eq(AccountEmails.Lang.ES), any());
        assertThat(login(email, NEW_PASSWORD, true)).isNotNull();
    }

    @Test
    @DisplayName("a wrong current password changes nothing")
    void changeNeedsCurrent() {
        String email = account(uniqueEmail(), OLD_PASSWORD);
        AuthService.Session elsewhere = login(email, OLD_PASSWORD, true);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> passwords.changePassword(idOf(email), "not my password",
                NEW_PASSWORD, "en", null, "198.51.100.7")))
                .isInstanceOf(ApiExceptions.BadRequestException.class)
                .extracting(e -> ((ApiExceptions.BadRequestException) e).code()).isEqualTo("current_password_incorrect");
        assertThat(refresh(elsewhere.refreshToken())).isNotNull();
        verify(mailer, never()).sendPasswordChanged(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("an account made with Google or Discord sets its first password through the emailed link")
    void federatedAccountSetsPassword() {
        String email = account(uniqueEmail(), null);
        long id = idOf(email);

        // It has no current password to give, so "change" refuses and points at the link.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> passwords.changePassword(id, "anything at all",
                NEW_PASSWORD, "en", null, "198.51.100.7")))
                .isInstanceOf(ApiExceptions.BadRequestException.class)
                .extracting(e -> ((ApiExceptions.BadRequestException) e).code()).isEqualTo("no_password");

        tx.executeWithoutResult(s -> passwords.sendSetPasswordLink(id, "fr", "198.51.100.7"));
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(mailer).sendResetLink(eq(email), anyString(), eq(AccountEmails.Lang.FR), token.capture(), eq(30L));

        resetWith(token.getValue(), NEW_PASSWORD);
        assertThat(login(email, NEW_PASSWORD, true)).isNotNull();

        // "Forgot password?" works for one too, without having to sign in first.
        String other = account(uniqueEmail(), null);
        resetWith(requestLink(other), NEW_PASSWORD);
        assertThat(login(other, NEW_PASSWORD, true)).isNotNull();
    }

    @Test
    @DisplayName("Remember me ticked keeps a 30-day session; unticked a short one, and refreshing keeps the kind")
    void rememberMe() {
        String email = account(uniqueEmail(), OLD_PASSWORD);

        AuthService.Session remembered = login(email, OLD_PASSWORD, true);
        AuthService.Session session = login(email, OLD_PASSWORD, false);
        AuthService.Session unspecified = login(email, OLD_PASSWORD, null);

        assertThat(remembered.persistent()).isTrue();
        assertThat(session.persistent()).isFalse();
        assertThat(unspecified.persistent()).isTrue();
        assertThat(expiryOf(remembered)).isEqualTo(clock.instant().plus(Duration.ofDays(30)));
        assertThat(expiryOf(session)).isEqualTo(clock.instant().plus(Duration.ofHours(12)));

        clock.advance(Duration.ofMinutes(20));
        AuthService.Session rotated = refresh(session.refreshToken());
        assertThat(rotated.persistent()).isFalse();
        assertThat(expiryOf(rotated)).isEqualTo(clock.instant().plus(Duration.ofHours(12)));
        assertThat(refresh(remembered.refreshToken()).persistent()).isTrue();
    }

    private Instant expiryOf(AuthService.Session s) {
        String hash;
        try {
            hash = sha256(s.refreshToken());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return jdbc.queryForObject("select expires_at from refresh_token where token_hash = ?",
                java.sql.Timestamp.class, hash).toInstant();
    }

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
