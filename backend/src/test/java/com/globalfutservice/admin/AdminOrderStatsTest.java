package com.globalfutservice.admin;

import com.globalfutservice.config.SecurityConfig;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.fulfilment.SupplierFulfilmentService;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.orders.web.OrderMapper;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The order queue's counters, which every operator's console polls: the counts are all
 * there, and revenue is not -- it is on the admin-only analytics endpoint now.
 */
@WebMvcTest(AdminOrderController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        // Dummy values for placeholders that have no default. Not secrets: nothing signs
        // or decrypts anything in this test.
        "GFS_JWT_SECRET=test-only-jwt-secret-000000000000000000000000000000",
        "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
        "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
class AdminOrderStatsTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private OrderRepository orders;
    @MockBean
    private OrderService orderService;
    @MockBean
    private OrderMapper mapper;
    @MockBean
    private CredentialVaultService vaultService;
    @MockBean
    private SupplierFulfilmentService supplierFulfilment;
    @MockBean
    private JwtService jwtService;

    private static UsernamePasswordAuthenticationToken as(AccountRole role) {
        AccountPrincipal p = new AccountPrincipal(1L, "acc_test", "t@example.test", role);
        return new UsernamePasswordAuthenticationToken(p, null, p.authorities());
    }

    @Test
    @DisplayName("gives an operator the queue counters and no revenue, and never runs the revenue query")
    void countersOnly() throws Exception {
        when(orders.countByStatus(OrderStatus.READY_FOR_DELIVERY)).thenReturn(5L);
        when(orders.countByStatus(OrderStatus.ON_HOLD)).thenReturn(1L);
        when(vaultService.countHeld()).thenReturn(3L);

        mvc.perform(get("/api/v1/admin/orders/stats").with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyForDelivery").value(5))
                .andExpect(jsonPath("$.onHold").value(1))
                .andExpect(jsonPath("$.credentialsHeld").value(3))
                .andExpect(jsonPath("$.awaitingPayment").exists())
                .andExpect(jsonPath("$.disputed").exists())
                .andExpect(jsonPath("$.revenueLast30dMinor").doesNotExist())
                .andExpect(jsonPath("$.revenueLast30dFormatted").doesNotExist());
        verify(orders, never()).revenueSince(any());
    }
}
