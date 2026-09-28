package com.globalfutservice.admin;

import com.globalfutservice.config.SecurityConfig;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dashboard's counts are staff's; its revenue is an admin's, and never rides along in
 * the response an operator gets.
 */
@WebMvcTest(AdminDashboardController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "GFS_JWT_SECRET=test-only-jwt-secret-000000000000000000000000000000",
        "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
        "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
class AdminDashboardSecurityTest {

    @Autowired
    private MockMvc mvc;

    @MockBean private AdminDashboardQueries queries;
    @MockBean private JwtService jwtService;

    private static UsernamePasswordAuthenticationToken as(AccountRole role) {
        AccountPrincipal p = new AccountPrincipal(1L, "acc_test", "t@example.test", role);
        return new UsernamePasswordAuthenticationToken(p, null, p.authorities());
    }

    @BeforeEach
    void stub() {
        when(queries.dashboard()).thenReturn(new AdminDashboardQueries.Dashboard(
                18, 14, 27, 24, 42, 31, List.of(), List.of()));
        when(queries.revenueToday()).thenReturn(List.of(
                new AdminDashboardQueries.CurrencyRevenue("INR", 5845000, "₹58,450.00", 4830000, "₹48,300.00"),
                new AdminDashboardQueries.CurrencyRevenue("GBP", 2399, "£23.99", 0, "£0.00")));
    }

    @Test
    @DisplayName("nobody signed in, and no customer, gets either")
    void outsiders() throws Exception {
        mvc.perform(get("/api/v1/admin/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/dashboard/revenue")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/dashboard").with(authentication(as(AccountRole.CUSTOMER))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an operator gets the counts, with no money in them")
    void operatorCounts() throws Exception {
        mvc.perform(get("/api/v1/admin/dashboard").with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newToday").value(18))
                .andExpect(jsonPath("$.pendingYesterday").value(24))
                .andExpect(jsonPath("$.revenue").doesNotExist())
                .andExpect(jsonPath("$.todayMinor").doesNotExist());
    }

    @Test
    @DisplayName("an operator is refused today's revenue, and the query never runs for them")
    void operatorNoRevenue() throws Exception {
        mvc.perform(get("/api/v1/admin/dashboard/revenue").with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isForbidden());
        verify(queries, never()).revenueToday();
    }

    @Test
    @DisplayName("an admin gets today's revenue per currency, rupees first")
    void adminRevenue() throws Exception {
        mvc.perform(get("/api/v1/admin/dashboard/revenue").with(authentication(as(AccountRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].currency").value("INR"))
                .andExpect(jsonPath("$[0].todayFormatted").value("₹58,450.00"))
                .andExpect(jsonPath("$[1].currency").value("GBP"));
    }
}
