package com.globalfutservice.admin;

import com.globalfutservice.config.SecurityConfig;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.identity.EmailVerificationService;
import com.globalfutservice.identity.SignupSettings;
import com.globalfutservice.identity.web.SignupController;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.HumanCheck;
import com.globalfutservice.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Pre-tick marketing consent" is an admin's to change; what the sign-up form starts with is
 * public, and says when the human check is off.
 */
@WebMvcTest({AdminSignupSettingsController.class, SignupController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "GFS_JWT_SECRET=test-only-jwt-secret-000000000000000000000000000000",
        "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
        "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
class AdminSignupSettingsSecurityTest {

    private static final String BASE = "/api/v1/admin/settings/signup";

    @Autowired
    private MockMvc mvc;

    @MockBean private SignupSettings settings;
    @MockBean private HumanCheck humanCheck;
    @MockBean private EmailVerificationService verification;
    @MockBean private AccountRepository accounts;
    @MockBean private JwtService jwtService;

    private static UsernamePasswordAuthenticationToken as(AccountRole role) {
        AccountPrincipal p = new AccountPrincipal(1L, "acc_staff", "t@example.test", role);
        return new UsernamePasswordAuthenticationToken(p, null, p.authorities());
    }

    @Test
    @DisplayName("only an admin reads or changes it")
    void adminOnly() throws Exception {
        String body = "{\"prefillMarketingConsent\":true}";
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE).with(authentication(as(AccountRole.CUSTOMER)))).andExpect(status().isForbidden());
        mvc.perform(get(BASE).with(authentication(as(AccountRole.OPERATOR)))).andExpect(status().isForbidden());
        mvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON).content(body)
                .with(authentication(as(AccountRole.OPERATOR)))).andExpect(status().isForbidden());
        verify(settings, never()).setPrefillMarketingConsent(anyBoolean(), org.mockito.ArgumentMatchers.any());

        when(settings.setPrefillMarketingConsent(true, 1L)).thenReturn(true);
        mvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(authentication(as(AccountRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prefillMarketingConsent").value(true));
        verify(settings).setPrefillMarketingConsent(eq(true), eq(1L));
    }

    @Test
    @DisplayName("the sign-up form's config is public: off by default, and no site key while Turnstile is off")
    void publicConfig() throws Exception {
        when(settings.prefillMarketingConsent()).thenReturn(false);
        when(humanCheck.siteKey()).thenReturn(null);
        mvc.perform(get("/api/v1/auth/signup-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prefillMarketingConsent").value(false))
                .andExpect(jsonPath("$.turnstileSiteKey").doesNotExist());

        when(humanCheck.siteKey()).thenReturn("0x4AAA-site");
        mvc.perform(get("/api/v1/auth/signup-config"))
                .andExpect(jsonPath("$.turnstileSiteKey").value("0x4AAA-site"));
    }
}
