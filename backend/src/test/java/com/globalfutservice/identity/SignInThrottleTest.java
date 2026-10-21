package com.globalfutservice.identity;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.identity.web.AuthDtos;
import com.globalfutservice.loyalty.LoyaltyService;
import com.globalfutservice.notify.AccountMailer;
import com.globalfutservice.security.JwtService;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Failed sign-ins slow down per address and per IP, and say nothing about which part was
 * wrong or whether the account exists. "Forgot password?" answers alike and is limited the
 * same two ways.
 */
class SignInThrottleTest {

    private static final String PASSWORD = "correct horse battery";

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-21T10:00:00Z"), ZoneOffset.UTC);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private AccountRepository accounts;
    private AuthService auth;
    private PasswordService passwords;
    private AccountMailer mailer;

    @BeforeEach
    void setUp() {
        AppProperties props = TestSecurity.props(5, 8, 3, 6);
        accounts = mock(AccountRepository.class);
        when(accounts.findByEmailNormalised(anyString())).thenReturn(Optional.empty());
        when(accounts.save(any())).thenAnswer(i -> i.getArgument(0));
        RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
        JwtService jwt = new JwtService(props, clock);
        SignInThrottle throttle = new SignInThrottle(props, clock);
        mailer = mock(AccountMailer.class);
        auth = new AuthService(accounts, refreshTokens, encoder, jwt, mock(LoyaltyService.class),
                throttle, props, clock);
        passwords = new PasswordService(accounts, mock(PasswordResetTokenRepository.class), refreshTokens,
                encoder, jwt, throttle, mailer, props, clock);
    }

    private AccountEntity existing(String email) {
        AccountEntity account = new AccountEntity("acc_" + email.hashCode(), email, encoder.encode(PASSWORD),
                AccountRole.CUSTOMER);
        when(accounts.findByEmailNormalised(AccountEntity.normalise(email))).thenReturn(Optional.of(account));
        return account;
    }

    private Throwable login(String email, String password, String ip) {
        return catchThrowable(() -> auth.login(new AuthDtos.LoginRequest(email, password, true), "test", ip));
    }

    @Test
    @DisplayName("a wrong password and an unknown address get the very same answer")
    void oneGenericAnswer() {
        existing("ada@example.test");

        Throwable wrongPassword = login("ada@example.test", "not the password", "198.51.100.1");
        Throwable noAccount = login("nobody@example.test", "not the password", "198.51.100.2");

        for (Throwable t : new Throwable[] {wrongPassword, noAccount}) {
            assertThat(t).isInstanceOf(ApiExceptions.BadRequestException.class)
                    .hasMessage("Email or password is incorrect.");
            assertThat(((ApiExceptions.BadRequestException) t).code()).isEqualTo("invalid_credentials");
        }
    }

    @Test
    @DisplayName("five failures at one address stop the sixth try, even with the right password, from any IP")
    void perEmail() {
        existing("ada@example.test");
        for (int i = 0; i < 5; i++) {
            assertThat(login("ada@example.test", "guess " + i, "198.51.100." + i))
                    .isInstanceOf(ApiExceptions.BadRequestException.class);
        }

        Throwable blocked = login("ADA@example.test", PASSWORD, "198.51.100.99");
        assertThat(blocked).isInstanceOf(ApiExceptions.TooManyRequestsException.class);
        assertThat(((ApiExceptions.TooManyRequestsException) blocked).code()).isEqualTo("too_many_attempts");
        assertThat(blocked.getMessage()).doesNotContain("password").doesNotContain("email");

        // Another address is not affected.
        existing("bob@example.test");
        assertThat(login("bob@example.test", PASSWORD, "198.51.100.99")).isNull();
    }

    @Test
    @DisplayName("an address with no account is slowed down exactly like one with an account")
    void perEmailWithoutAccount() {
        for (int i = 0; i < 5; i++) {
            assertThat(login("ghost@example.test", "guess " + i, "198.51.100." + i))
                    .isInstanceOf(ApiExceptions.BadRequestException.class);
        }
        assertThat(login("ghost@example.test", "guess", "198.51.100.50"))
                .isInstanceOf(ApiExceptions.TooManyRequestsException.class);
    }

    @Test
    @DisplayName("failures spread over many addresses from one IP stop that IP, and only that IP")
    void perIp() {
        existing("carol@example.test");
        for (int i = 0; i < 8; i++) {
            assertThat(login("victim" + i + "@example.test", "Summer2026!", "203.0.113.5"))
                    .isInstanceOf(ApiExceptions.BadRequestException.class);
        }
        assertThat(login("carol@example.test", PASSWORD, "203.0.113.5"))
                .isInstanceOf(ApiExceptions.TooManyRequestsException.class);
        assertThat(login("carol@example.test", PASSWORD, "203.0.113.6")).isNull();
    }

    @Test
    @DisplayName("successful sign-ins do not use up the budget")
    void successIsFree() {
        existing("dan@example.test");
        for (int i = 0; i < 12; i++) {
            assertThat(login("dan@example.test", PASSWORD, "203.0.113.7")).isNull();
        }
    }

    @Test
    @DisplayName("forgot password: the same quiet result for any address, and limited per address and per IP")
    void forgotLimits() {
        existing("erin@example.test");
        for (int i = 0; i < 3; i++) {
            passwords.requestReset("erin@example.test", "en", "203.0.113." + (20 + i));
        }
        verify(mailer, org.mockito.Mockito.times(3)).sendResetLink(anyString(), anyString(), any(), anyString(),
                anyLong());
        assertThatThrownBy(() -> passwords.requestReset("Erin@example.test", "en", "203.0.113.30"))
                .isInstanceOf(ApiExceptions.TooManyRequestsException.class);

        // Unknown addresses: nothing is sent, nothing is thrown, until the IP has asked too often.
        for (int i = 0; i < 6; i++) {
            passwords.requestReset("ghost" + i + "@example.test", "es", "203.0.113.40");
        }
        assertThatThrownBy(() -> passwords.requestReset("ghost9@example.test", "es", "203.0.113.40"))
                .isInstanceOf(ApiExceptions.TooManyRequestsException.class);
        verify(mailer, never()).sendResetLink(org.mockito.ArgumentMatchers.startsWith("ghost"), anyString(), any(),
                anyString(), anyLong());
    }
}
