package com.globalfutservice.admin;

import com.globalfutservice.config.SecurityConfig;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.marketing.CampaignAudience;
import com.globalfutservice.marketing.CampaignEntity;
import com.globalfutservice.marketing.CampaignService;
import com.globalfutservice.notify.email.TransactionalEmails;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may reach the campaign builder's new endpoints.
 *
 * <p>The real {@link SecurityConfig} and the real method security, not a copy of either:
 * the URL rule ({@code /api/v1/admin/**} needs OPERATOR) and the controller's class-level
 * {@code hasRole('ADMIN')} both have to hold, and a campaign reaches every opted-in
 * customer at once, so "an operator can create one" is the failure this exists to catch.
 * Only the service is mocked; nothing here needs a database.
 */
@WebMvcTest(AdminCampaignController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        // Dummy values for placeholders that have no default. Not secrets: nothing signs
        // or decrypts anything in this test.
        "GFS_JWT_SECRET=test-only-jwt-secret-000000000000000000000000000000",
        "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
        "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
class AdminCampaignSecurityTest {

    private static final String DETAILS = """
            {"title":"TOTY","subject":"TOTY is here","type":"COINS","promoTitle":"COINS SALE",
             "offerText":"15% OFF","promoCode":"HUNTER10","description":"Team of the Year.",
             "showButton":true,"showPromoCode":true,"trackingEnabled":true}
            """;

    @Autowired
    private MockMvc mvc;

    @MockBean
    private CampaignService campaigns;

    @MockBean
    private JwtService jwtService;

    @BeforeEach
    void stubService() {
        CampaignEntity draft = new CampaignEntity("TOTY", "TOTY is here", "COINS SALE", "Body",
                CampaignAudience.ALL_OPTED_IN, 1L);
        when(campaigns.createDraft(any(), anyLong())).thenReturn(draft);
        when(campaigns.replaceDetails(anyString(), any())).thenReturn(draft);
        when(campaigns.previewDetails(any(), any(), any(), isNull()))
                .thenReturn(new TransactionalEmails.Rendered("s", "<html>preview</html>", "t"));
        when(campaigns.replaceContent(anyString(), any(), any())).thenReturn(draft);
        when(campaigns.chooseAudience(anyString(), any())).thenReturn(draft);
        when(campaigns.sendTest(anyString(), anyLong())).thenReturn("t@example.test");
        when(campaigns.quota()).thenReturn(new CampaignService.SendQuota(100, 3, 97));
    }

    private static UsernamePasswordAuthenticationToken as(AccountRole role) {
        AccountPrincipal p = new AccountPrincipal(1L, "acc_test", "t@example.test", role);
        return new UsernamePasswordAuthenticationToken(p, null, p.authorities());
    }

    /** Every endpoint the builder added, each with a body that is valid for it. */
    static Stream<Arguments> newEndpoints() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/api/v1/admin/campaigns/drafts", DETAILS),
                Arguments.of(HttpMethod.PUT, "/api/v1/admin/campaigns/camp_x/details", DETAILS),
                Arguments.of(HttpMethod.POST, "/api/v1/admin/campaigns/preview", DETAILS),
                Arguments.of(HttpMethod.PUT, "/api/v1/admin/campaigns/camp_x/content",
                        "{\"kicker\":\"Team of the Year\",\"subline\":\"Build your dream squad\"}"),
                Arguments.of(HttpMethod.PUT, "/api/v1/admin/campaigns/camp_x/audience",
                        "{\"audience\":\"COINS_BUYERS\"}"),
                Arguments.of(HttpMethod.POST, "/api/v1/admin/campaigns/camp_x/test", ""),
                Arguments.of(HttpMethod.GET, "/api/v1/admin/campaigns/quota", ""));
    }

    private static MockHttpServletRequestBuilder call(HttpMethod method, String path, String body) {
        MockHttpServletRequestBuilder r = request(method, path).accept(MediaType.ALL);
        return body.isEmpty() ? r : r.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Nested
    class Access {

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.globalfutservice.admin.AdminCampaignSecurityTest#newEndpoints")
        @DisplayName("refuses a caller who is not signed in")
        void anonymous(HttpMethod method, String path, String body) throws Exception {
            mvc.perform(call(method, path, body)).andExpect(status().isUnauthorized());
        }

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.globalfutservice.admin.AdminCampaignSecurityTest#newEndpoints")
        @DisplayName("refuses a customer")
        void customer(HttpMethod method, String path, String body) throws Exception {
            mvc.perform(call(method, path, body).with(authentication(as(AccountRole.CUSTOMER))))
                    .andExpect(status().isForbidden());
        }

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.globalfutservice.admin.AdminCampaignSecurityTest#newEndpoints")
        @DisplayName("refuses an operator: campaigns are admin-only, though the console is not")
        void operator(HttpMethod method, String path, String body) throws Exception {
            mvc.perform(call(method, path, body).with(authentication(as(AccountRole.OPERATOR))))
                    .andExpect(status().isForbidden());
        }

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.globalfutservice.admin.AdminCampaignSecurityTest#newEndpoints")
        @DisplayName("admits an admin")
        void admin(HttpMethod method, String path, String body) throws Exception {
            mvc.perform(call(method, path, body).with(authentication(as(AccountRole.ADMIN))))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    class Preview {

        private MockHttpServletRequestBuilder preview(MediaType accept) {
            return request(HttpMethod.POST, "/api/v1/admin/campaigns/preview")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(accept)
                    .content(DETAILS)
                    .with(authentication(as(AccountRole.ADMIN)));
        }

        @Test
        @DisplayName("answers a request for HTML with HTML, uncached")
        void html() throws Exception {
            mvc.perform(preview(MediaType.TEXT_HTML))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                    .andExpect(content().string("<html>preview</html>"))
                    .andExpect(result -> org.assertj.core.api.Assertions
                            .assertThat(result.getResponse().getHeader("Cache-Control"))
                            .contains("no-store"));
        }

        @Test
        @DisplayName("answers a JSON-only request with 406, not 500 — the failure behind the old preview")
        void jsonOnlyIs406() throws Exception {
            mvc.perform(preview(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotAcceptable())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
            // Before campaign ids were constrained, this request fell through to
            // POST /{publicId} and tried to edit a campaign called "preview".
            verify(campaigns, never()).update(anyString(), any(), any(), any(), any(), any(),
                    any(), any());
        }

        @Test
        @DisplayName("renders an empty form rather than refusing it — it runs while the admin types")
        void toleratesAnEmptyForm() throws Exception {
            mvc.perform(request(HttpMethod.POST, "/api/v1/admin/campaigns/preview")
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_HTML)
                            .content("{}")
                            .with(authentication(as(AccountRole.ADMIN))))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    class Validation {

        @Test
        @DisplayName("the hero lines are held to one line each: 40 above, 60 below")
        void heroLineLimits() throws Exception {
            mvc.perform(request(HttpMethod.PUT, "/api/v1/admin/campaigns/camp_x/content")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"kicker\":\"" + "k".repeat(41) + "\"}")
                            .with(authentication(as(AccountRole.ADMIN))))
                    .andExpect(status().isBadRequest());
            mvc.perform(request(HttpMethod.PUT, "/api/v1/admin/campaigns/camp_x/content")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"subline\":\"" + "s".repeat(61) + "\"}")
                            .with(authentication(as(AccountRole.ADMIN))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("an audience outside the fixed segments is refused, not stored")
        void unknownAudience() throws Exception {
            mvc.perform(request(HttpMethod.PUT, "/api/v1/admin/campaigns/camp_x/audience")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"audience\":\"EVERYONE\"}")
                            .with(authentication(as(AccountRole.ADMIN))))
                    .andExpect(status().isBadRequest());
            mvc.perform(request(HttpMethod.PUT, "/api/v1/admin/campaigns/camp_x/audience")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .with(authentication(as(AccountRole.ADMIN))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a test send takes no address: any address in the body is ignored")
        void testSendTakesNoAddress() throws Exception {
            mvc.perform(request(HttpMethod.POST, "/api/v1/admin/campaigns/camp_x/test")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"to\":\"stranger@example.test\"}")
                            .with(authentication(as(AccountRole.ADMIN))))
                    .andExpect(status().isOk());
            // The caller's own account id, never an address from the request.
            org.mockito.Mockito.verify(campaigns).sendTest("camp_x", 1L);
        }

        @Test
        @DisplayName("saving the step enforces the design's limits")
        void savingIsStrict() throws Exception {
            String longSubject = DETAILS.replace("TOTY is here", "s".repeat(101));
            mvc.perform(request(HttpMethod.POST, "/api/v1/admin/campaigns/drafts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(longSubject)
                            .with(authentication(as(AccountRole.ADMIN))))
                    .andExpect(status().isBadRequest());
        }
    }
}
