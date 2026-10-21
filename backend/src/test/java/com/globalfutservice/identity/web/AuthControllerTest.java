package com.globalfutservice.identity.web;

import com.globalfutservice.config.SecurityConfig;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.identity.AuthService;
import com.globalfutservice.identity.PasswordService;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.JwtService;
import com.globalfutservice.web.ApiExceptions;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The sign-in and password endpoints over HTTP: the two kinds of refresh cookie "Remember me"
 * chooses between, and the fixed answers the storefront words in each language.
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "GFS_JWT_SECRET=test-only-jwt-secret-000000000000000000000000000000",
        "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
        "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
class AuthControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean private AuthService authService;
    @MockBean private PasswordService passwordService;
    @MockBean private AccountRepository accounts;
    @MockBean private JwtService jwtService;

    private final AccountEntity account = new AccountEntity("acc_ada", "ada@example.test", "hash",
            AccountRole.CUSTOMER);

    @BeforeEach
    void describe() {
        when(authService.describe(any())).thenReturn(new AuthDtos.AccountResponse(
                "acc_ada", "ada@example.test", null, "CUSTOMER", 0, 0, "₹0.00", true, null, true));
    }

    private String loginBody(Boolean rememberMe) {
        return "{\"email\":\"ada@example.test\",\"password\":\"correct horse battery\""
                + (rememberMe == null ? "" : ",\"rememberMe\":" + rememberMe) + "}";
    }

    private String setCookie(Boolean rememberMe, boolean persistent) throws Exception {
        when(authService.login(any(), any(), any()))
                .thenReturn(new AuthService.Session("access", "refresh-raw", account, persistent));
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(rememberMe)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
    }

    @Test
    @DisplayName("Remember me ticked: the long-lived refresh cookie, with a Max-Age")
    void rememberedCookie() throws Exception {
        String cookie = setCookie(true, true);
        assertThat(cookie).startsWith("gfs_refresh=refresh-raw").contains("Max-Age=2592000")
                .contains("HttpOnly").contains("SameSite=Strict").contains("Path=/api/v1/auth");
        verify(authService).login(eq(new AuthDtos.LoginRequest("ada@example.test", "correct horse battery", true)),
                any(), any());
    }

    @Test
    @DisplayName("Remember me unticked: a session cookie, with no Max-Age or Expires, gone when the browser closes")
    void sessionCookie() throws Exception {
        String cookie = setCookie(false, false);
        assertThat(cookie).startsWith("gfs_refresh=refresh-raw").doesNotContain("Max-Age").doesNotContain("Expires")
                .contains("HttpOnly").contains("SameSite=Strict");
        verify(authService).login(eq(new AuthDtos.LoginRequest("ada@example.test", "correct horse battery", false)),
                any(), any());
    }

    @Test
    @DisplayName("a failed sign-in answers 400 invalid_credentials with the one generic sentence")
    void genericFailure() throws Exception {
        when(authService.login(any(), any(), any())).thenThrow(
                new ApiExceptions.BadRequestException("invalid_credentials", "Email or password is incorrect."));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginBody(true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_credentials"))
                .andExpect(jsonPath("$.message").value("Email or password is incorrect."))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    @DisplayName("forgot password always answers the same sentence")
    void forgotReply() throws Exception {
        for (String email : new String[] {"ada@example.test", "nobody@example.test"}) {
            mvc.perform(post("/api/v1/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + email + "\",\"language\":\"es\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message")
                            .value("If an account exists for that email, we've sent a reset link."));
        }
        verify(passwordService).requestReset(eq("nobody@example.test"), eq("es"), anyString());
    }

    @Test
    @DisplayName("too many requests is a 429 with a retry time")
    void forgotLimited() throws Exception {
        doThrow(new ApiExceptions.TooManyRequestsException("too_many_attempts", "Too many attempts.",
                java.time.Instant.parse("2026-10-21T10:20:00Z")))
                .when(passwordService).requestReset(any(), any(), any());
        mvc.perform(post("/api/v1/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ada@example.test\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("too_many_attempts"));
    }

    @Test
    @DisplayName("a reset needs the sign-up length rule, and on success clears this browser's refresh cookie")
    void resetPassword() throws Exception {
        mvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"t\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.password").exists());

        mvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"t\",\"password\":\"a long enough passphrase\",\"language\":\"fr\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password updated, please sign in."))
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString("Max-Age=0")));
        verify(passwordService).resetPassword("t", "a long enough passphrase", "fr");
    }

    @Test
    @DisplayName("changing the password needs a signed-in account, and passes on this device's refresh cookie")
    void changePassword() throws Exception {
        String body = "{\"currentPassword\":\"old one\",\"newPassword\":\"a long enough passphrase\"}";
        mvc.perform(post("/api/v1/auth/password/change").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        AccountPrincipal p = new AccountPrincipal(7L, "acc_ada", "ada@example.test", AccountRole.CUSTOMER);
        mvc.perform(post("/api/v1/auth/password/change").contentType(MediaType.APPLICATION_JSON).content(body)
                        .cookie(new Cookie("gfs_refresh", "this-device"))
                        .with(authentication(new UsernamePasswordAuthenticationToken(p, null, p.authorities()))))
                .andExpect(status().isNoContent());
        verify(passwordService).changePassword(eq(7L), eq("old one"), eq("a long enough passphrase"), eq(null),
                eq("this-device"), any());

        mvc.perform(post("/api/v1/auth/password/set-link").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"fr\"}")
                        .with(authentication(new UsernamePasswordAuthenticationToken(p, null, p.authorities()))))
                .andExpect(status().isNoContent());
        verify(passwordService).sendSetPasswordLink(eq(7L), eq("fr"), any());
    }
}
