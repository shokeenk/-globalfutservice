package com.globalfutservice.payments.web;

import com.globalfutservice.admin.AdminOrderPaymentController;
import com.globalfutservice.config.SecurityConfig;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.payments.ResumePaymentService;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.JwtService;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Completing a payment is the signed-in owner's alone, by the same rule as viewing the order:
 * somebody else's order and one that does not exist are both "not found".
 */
@WebMvcTest({OrderPaymentController.class, AdminOrderPaymentController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        // Dummy values for placeholders that have no default. Not secrets: nothing signs
        // or decrypts anything in this test.
        "GFS_JWT_SECRET=test-only-jwt-secret-000000000000000000000000000000",
        "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
        "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
class OrderPaymentSecurityTest {

    private static final String MINE = "GFS-26-MINE0001";
    private static final String THEIRS = "GFS-26-THEIRS01";

    @Autowired
    private MockMvc mvc;

    @MockBean private ResumePaymentService payments;
    @MockBean private OrderService orderService;
    @MockBean private JwtService jwtService;

    private final OrderEntity mine = mock(OrderEntity.class);

    private static UsernamePasswordAuthenticationToken as(long id, AccountRole role) {
        AccountPrincipal p = new AccountPrincipal(id, "acc_" + id, id + "@example.test", role);
        return new UsernamePasswordAuthenticationToken(p, null, p.authorities());
    }

    @BeforeEach
    void stub() {
        when(orderService.requireOwned(MINE, 1L)).thenReturn(mine);
        // Somebody else's order, and one that does not exist: the same answer.
        when(orderService.requireOwned(THEIRS, 1L)).thenThrow(new ApiExceptions.NotFoundException("No such order."));
        when(orderService.requireOwned("GFS-26-NOSUCH01", 1L))
                .thenThrow(new ApiExceptions.NotFoundException("No such order."));
        when(orderService.requireAny(MINE)).thenReturn(mine);
        when(payments.view(eq(mine), any())).thenReturn(new ResumePaymentService.View(MINE, "AWAITING_PAYMENT",
                "UNPAID", Instant.parse("2026-10-08T12:00:00Z"), "EUR", 9225, "€92.25", null, List.of(), true,
                null, null, null, null, false));
    }

    private static MockHttpServletRequestBuilder[] actions(String ref) {
        String base = "/api/v1/orders/" + ref + "/payment";
        return new MockHttpServletRequestBuilder[] {
                get(base),
                post(base + "/payop/options").contentType(MediaType.APPLICATION_JSON).content("{\"country\":\"DE\"}"),
                post(base + "/payop/invoices").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"t\"}"),
                post(base + "/claims").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"UPI\",\"reference\":\"UTR12345678\"}"),
                post(base + "/coaching-slot").contentType(MediaType.APPLICATION_JSON).content("{}"),
        };
    }

    @Test
    @DisplayName("nobody signed in gets nothing, not even whether the order exists")
    void anonymous() throws Exception {
        for (MockHttpServletRequestBuilder request : actions(MINE)) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        verify(orderService, never()).requireOwned(anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {THEIRS, "GFS-26-NOSUCH01"})
    @DisplayName("somebody else's order, or none: the ordinary not found, for every action")
    void notTheOwner(String ref) throws Exception {
        for (MockHttpServletRequestBuilder request : actions(ref)) {
            mvc.perform(request.with(authentication(as(1L, AccountRole.CUSTOMER)))).andExpect(status().isNotFound());
        }
        verify(payments, never()).submitClaim(any(), any(), any());
        verify(payments, never()).startPayop(any(), any(), any());
    }

    @Test
    @DisplayName("the owner gets the order's payment, never cached")
    void owner() throws Exception {
        mvc.perform(get("/api/v1/orders/" + MINE + "/payment").with(authentication(as(1L, AccountRole.CUSTOMER))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.publicRef").value(MINE))
                .andExpect(jsonPath("$.paymentState").value("UNPAID"))
                .andExpect(jsonPath("$.amountDueFormatted").value("€92.25"));
    }

    @Test
    @DisplayName("too many new invoices: 429, with when the next can be opened")
    void limited() throws Exception {
        when(payments.startPayop(eq(mine), eq("t"), any())).thenThrow(new ApiExceptions.TooManyRequestsException(
                "payment_attempts_order", "Try again later.", Instant.parse("2026-10-06T13:00:00Z")));
        mvc.perform(post("/api/v1/orders/" + MINE + "/payment/payop/invoices")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"t\"}")
                        .with(authentication(as(1L, AccountRole.CUSTOMER))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("payment_attempts_order"))
                .andExpect(jsonPath("$.details.retryAt[0]").value("2026-10-06T13:00:00Z"));
    }

    @Test
    @DisplayName("no amount is accepted: a claim is a method and a reference, a Payop start a token")
    void noAmounts() throws Exception {
        com.globalfutservice.payments.ManualPaymentClaimEntity claim =
                mock(com.globalfutservice.payments.ManualPaymentClaimEntity.class);
        when(claim.getMethod()).thenReturn(com.globalfutservice.domain.payments.ManualPaymentMethod.UPI);
        when(claim.getStatus()).thenReturn(com.globalfutservice.domain.payments.ClaimStatus.SUBMITTED);
        when(claim.getReference()).thenReturn("UTR12345678");
        when(payments.submitClaim(any(), any(), any())).thenReturn(claim);

        // An amount in the body is not a field the endpoint has: it is ignored, never read.
        mvc.perform(post("/api/v1/orders/" + MINE + "/payment/claims").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"UPI\",\"reference\":\"UTR12345678\",\"amountMinor\":1}")
                        .with(authentication(as(1L, AccountRole.CUSTOMER))))
                .andExpect(status().isCreated());
        verify(payments).submitClaim(eq(mine), eq(com.globalfutservice.domain.payments.ManualPaymentMethod.UPI),
                eq("UTR12345678"));
    }

    @Test
    @DisplayName("staff see how the customer is paying; a customer does not")
    void staffView() throws Exception {
        when(payments.staffView(mine)).thenReturn(new ResumePaymentService.StaffView(null, null, List.of()));
        mvc.perform(get("/api/v1/admin/orders/" + MINE + "/payment").with(authentication(as(1L, AccountRole.CUSTOMER))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/orders/" + MINE + "/payment").with(authentication(as(2L, AccountRole.OPERATOR))))
                .andExpect(status().isOk());
    }
}
