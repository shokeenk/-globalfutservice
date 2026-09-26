package com.globalfutservice.admin;

import com.globalfutservice.config.SecurityConfig;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The revenue figure behind the Analytics page: the same value the order queue used to
 * carry, and only for an admin.
 *
 * <p>The real {@link SecurityConfig} and method security; only the repository is mocked,
 * so the query itself is not run here -- it is the unchanged
 * {@link OrderRepository#revenueSince}.
 */
@WebMvcTest(AdminAnalyticsController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        // Dummy values for placeholders that have no default. Not secrets: nothing signs
        // or decrypts anything in this test.
        "GFS_JWT_SECRET=test-only-jwt-secret-000000000000000000000000000000",
        "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
        "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
class AdminAnalyticsControllerTest {

    private static final String REVENUE = "/api/v1/admin/analytics/revenue";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private OrderRepository orders;

    @MockBean
    private JwtService jwtService;

    private static UsernamePasswordAuthenticationToken as(AccountRole role) {
        AccountPrincipal p = new AccountPrincipal(1L, "acc_test", "t@example.test", role);
        return new UsernamePasswordAuthenticationToken(p, null, p.authorities());
    }

    @Test
    @DisplayName("gives an admin the thirty-day figure, in minor units and formatted as before")
    void admin() throws Exception {
        when(orders.revenueSince(any())).thenReturn(1_234_550L);

        Instant before = Instant.now();
        mvc.perform(get(REVENUE).with(authentication(as(AccountRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revenueLast30dMinor").value(1_234_550L))
                .andExpect(jsonPath("$.revenueLast30dFormatted")
                        .value(Money.ofMinor(1_234_550L, Currency.INR).format()));
        Instant after = Instant.now();

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(orders).revenueSince(since.capture());
        assertThat(since.getValue())
                .isBetween(before.minus(Duration.ofDays(30)), after.minus(Duration.ofDays(30)));
    }

    @Test
    @DisplayName("says nothing to an operator, and does not run the query")
    void operator() throws Exception {
        mvc.perform(get(REVENUE).with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isForbidden());
        verify(orders, never()).revenueSince(any());
    }

    @Test
    @DisplayName("refuses a customer")
    void customer() throws Exception {
        mvc.perform(get(REVENUE).with(authentication(as(AccountRole.CUSTOMER))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("refuses a caller who is not signed in")
    void anonymous() throws Exception {
        mvc.perform(get(REVENUE)).andExpect(status().isUnauthorized());
    }
}
