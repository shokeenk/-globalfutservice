package com.globalfutservice.admin;

import java.util.List;
import java.util.Map;

import com.globalfutservice.catalog.CoinPricingService;
import com.globalfutservice.config.SecurityConfig;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.pricing.PricingEngine;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.security.AccountPrincipal;
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
import org.springframework.test.web.servlet.RequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What coins cost is an admin's to set: nobody else reads or changes the structures. */
@WebMvcTest(AdminCoinPricingController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "GFS_JWT_SECRET=test-only-jwt-secret-000000000000000000000000000000",
        "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
        "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
class AdminCoinPricingSecurityTest {

    private static final String BASE = "/api/v1/admin/coin-pricing";
    private static final String DRAFT = """
            {"minK": 50, "maxK": 1000, "stepK": 10, "rates": [{"currency": "INR", "per100k": 1300}]}
            """;

    @Autowired
    private MockMvc mvc;

    @MockBean private CoinPricingService pricing;
    @MockBean private PricingEngine engine;
    @MockBean private com.globalfutservice.identity.AccountRepository accounts;
    @MockBean private JwtService jwtService;

    private static UsernamePasswordAuthenticationToken as(AccountRole role) {
        AccountPrincipal p = new AccountPrincipal(1L, "acc_staff", "staff@example.test", role);
        return new UsernamePasswordAuthenticationToken(p, null, p.authorities());
    }

    private static List<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> every() {
        return List.of(
                get(BASE),
                get(BASE + "/CONSOLE/history"),
                post(BASE + "/CONSOLE/preview").contentType(MediaType.APPLICATION_JSON).content(DRAFT),
                put(BASE + "/CONSOLE").contentType(MediaType.APPLICATION_JSON).content(DRAFT));
    }

    @Test
    @DisplayName("strangers, customers and operators reach none of it, and nothing is read or saved")
    void notAdmins() throws Exception {
        for (var request : every()) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        for (AccountRole role : List.of(AccountRole.CUSTOMER, AccountRole.OPERATOR)) {
            for (var request : every()) {
                mvc.perform((RequestBuilder) request.with(authentication(as(role)))).andExpect(status().isForbidden());
            }
        }
        verifyNoInteractions(pricing, engine);
    }

    @Test
    @DisplayName("an admin reads both structures and the rules they are checked against, never cached")
    void adminReads() throws Exception {
        when(pricing.live()).thenReturn(Map.of());
        when(pricing.requiredCurrencies()).thenReturn(List.of(Currency.INR));
        when(pricing.vendorMinTransferK()).thenReturn(50);
        mvc.perform(get(BASE).with(authentication(as(AccountRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.limits.maxCapK").value(10_000))
                .andExpect(jsonPath("$.limits.smallestStepK").value(10))
                .andExpect(jsonPath("$.limits.vendorMinTransferK").value(50))
                .andExpect(jsonPath("$.limits.defaultQuickPicksK[4]").value(1000))
                .andExpect(jsonPath("$.currencies[0]").value("INR"));
    }

    @Test
    @DisplayName("an admin's save that cannot be read as a structure is refused before anything is saved")
    void refusedBeforeSaving() throws Exception {
        mvc.perform(put(BASE + "/CONSOLE").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxK\": 1000, \"stepK\": 10, \"rates\": []}")
                        .with(authentication(as(AccountRole.ADMIN))))
                .andExpect(status().isBadRequest());
        mvc.perform(put(BASE + "/XBOX").contentType(MediaType.APPLICATION_JSON).content(DRAFT)
                        .with(authentication(as(AccountRole.ADMIN))))
                .andExpect(status().isNotFound());
        verify(pricing, never()).save(any(), any(), any());
    }
}
