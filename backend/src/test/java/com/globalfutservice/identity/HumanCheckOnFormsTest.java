package com.globalfutservice.identity;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.identity.web.AuthDtos;
import com.globalfutservice.loyalty.LoyaltyService;
import com.globalfutservice.marketing.MarketingConsent;
import com.globalfutservice.notify.AccountMailer;
import com.globalfutservice.security.HumanCheck;
import com.globalfutservice.security.JwtService;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Where the human check is asked for: sign-up, forgot-password, and sign-in once an address
 * or an IP has failed three times. And that none of it is asked for until Turnstile is set up.
 */
class HumanCheckOnFormsTest {

    private static final String PASSWORD = "correct horse battery";
    private static final String GOOD = "token-cloudflare-accepts";

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-21T10:00:00Z"), ZoneOffset.UTC);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private AuthService auth;
    private PasswordService passwords;

    private void build(boolean turnstile) {
        AppProperties props = TestSecurity.props();
        HumanCheck check = turnstile
                ? new HumanCheck(new AppProperties.Turnstile("site", "secret", "https://cf.test/verify"),
                        new ObjectMapper(), (url, form) -> "{\"success\":" + form.contains("response=" + GOOD) + "}")
                : TestSecurity.humanCheckOff();
        when(accounts.findByEmailNormalised(anyString())).thenReturn(Optional.empty());
        when(accounts.save(any())).thenAnswer(i -> i.getArgument(0));
        JwtService jwt = new JwtService(props, clock);
        SignInThrottle throttle = new SignInThrottle(props, clock);
        RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
        auth = new AuthService(accounts, refreshTokens, encoder, jwt, mock(LoyaltyService.class), throttle, check,
                mock(MarketingConsent.class), mock(EmailVerificationService.class), props, clock);
        passwords = new PasswordService(accounts, mock(PasswordResetTokenRepository.class), refreshTokens, encoder,
                jwt, throttle, mock(AccountMailer.class), check, props, clock);
    }

    private void account(String email) {
        AccountEntity a = new AccountEntity("acc_x", email, encoder.encode(PASSWORD), AccountRole.CUSTOMER);
        when(accounts.findByEmailNormalised(AccountEntity.normalise(email))).thenReturn(Optional.of(a));
    }

    private Throwable login(String email, String password, String ip, String token) {
        return catchThrowable(() -> auth.login(new AuthDtos.LoginRequest(email, password, true, token), "t", ip));
    }

    private Throwable register(String token) {
        return catchThrowable(() -> auth.register(new AuthDtos.RegisterRequest("new@example.test", PASSWORD, null, null,
                null, true, false, "en", token), "t", "198.51.100.9"));
    }

    private static String code(Throwable t) {
        return t instanceof ApiExceptions.BadRequestException b ? b.code() : String.valueOf(t);
    }

    @Test
    @DisplayName("sign-in: three failures need no check; from the fourth try the check is asked for")
    void signInAfterThreeFailures() {
        build(true);
        account("ada@example.test");
        for (int i = 0; i < 3; i++) {
            assertThat(code(login("ada@example.test", "guess " + i, "198.51.100." + i, null)))
                    .isEqualTo("invalid_credentials");
        }
        assertThat(code(login("ada@example.test", PASSWORD, "198.51.100.50", null))).isEqualTo(HumanCheck.REQUIRED);
        assertThat(code(login("ada@example.test", PASSWORD, "198.51.100.50", "a-forged-token")))
                .isEqualTo(HumanCheck.FAILED);
        assertThat(login("ada@example.test", PASSWORD, "198.51.100.50", GOOD)).isNull();
    }

    @Test
    @DisplayName("sign-in: three failures from one IP, over different addresses, ask that IP for the check")
    void signInAfterThreeFailuresFromOneIp() {
        build(true);
        account("bob@example.test");
        for (int i = 0; i < 3; i++) {
            login("someone" + i + "@example.test", "Summer2026!", "203.0.113.5", null);
        }
        assertThat(code(login("bob@example.test", PASSWORD, "203.0.113.5", null))).isEqualTo(HumanCheck.REQUIRED);
        assertThat(login("bob@example.test", PASSWORD, "203.0.113.6", null)).isNull();
    }

    @Test
    @DisplayName("a first sign-in never sees the check")
    void signInFirstTry() {
        build(true);
        account("cat@example.test");
        assertThat(login("cat@example.test", PASSWORD, "198.51.100.1", null)).isNull();
    }

    @Test
    @DisplayName("sign-up and forgot-password ask for it every time")
    void signUpAndForgot() {
        build(true);
        assertThat(code(register(null))).isEqualTo(HumanCheck.REQUIRED);
        assertThat(code(register("forged"))).isEqualTo(HumanCheck.FAILED);
        assertThat(register(GOOD)).isNull();

        assertThat(code(catchThrowable(() -> passwords.requestReset("a@example.test", "en", null, "198.51.100.1"))))
                .isEqualTo(HumanCheck.REQUIRED);
        assertThat(catchThrowable(() -> passwords.requestReset("a@example.test", "en", GOOD, "198.51.100.1")))
                .isNull();
    }

    @Test
    @DisplayName("Turnstile not configured: sign-up, forgot-password and sign-in after failures all go through")
    void skippedWhenNotConfigured() {
        build(false);
        account("dan@example.test");
        assertThat(register(null)).isNull();
        assertThat(catchThrowable(() -> passwords.requestReset("dan@example.test", "en", null, "198.51.100.1")))
                .isNull();
        for (int i = 0; i < 3; i++) {
            login("dan@example.test", "guess " + i, "198.51.100.2", null);
        }
        assertThat(login("dan@example.test", PASSWORD, "198.51.100.2", null)).isNull();
    }
}
