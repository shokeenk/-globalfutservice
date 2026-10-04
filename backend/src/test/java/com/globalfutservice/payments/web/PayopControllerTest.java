package com.globalfutservice.payments.web;

import java.util.List;
import java.util.Optional;

import com.globalfutservice.config.SecurityConfig;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.payments.payop.PayopCallbackService;
import com.globalfutservice.payments.payop.PayopCheckoutService;
import com.globalfutservice.payments.payop.PayopClient;
import com.globalfutservice.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who can reach the Payop endpoints, and the IPN's address check as it runs in the web layer. */
@WebMvcTest(PayopController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "GFS_JWT_SECRET=test-only-jwt-secret-000000000000000000000000000000",
        "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
        "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "GFS_PAYOP_ENABLED=true",
})
class PayopControllerTest {

    private static final String CALLBACK = "/api/v1/payments/payop/callback";
    private static final String PAYOP = "18.199.249.46";
    private static final PayopCallbackService.Ipn IPN = new PayopCallbackService.Ipn(
            "d024f697-ba2d-456f-910e-4d7fdfd338dd", "dca59ca5-be19-470d-9494-9b76944e0241", 2, 1,
            "GFS-26-EUR00001", null);

    @Autowired
    private MockMvc mvc;

    @MockBean private PayopCheckoutService checkout;
    @MockBean private PayopCallbackService callbacks;
    @MockBean private OrderService orders;
    @MockBean private JwtService jwtService;

    @BeforeEach
    void setUp() {
        when(callbacks.parse(any())).thenReturn(Optional.of(IPN));
    }

    private static RequestPostProcessor from(String peer) {
        return request -> {
            request.setRemoteAddr(peer);
            return request;
        };
    }

    private static MockHttpServletRequestBuilder ipn() {
        return post(CALLBACK).contentType(MediaType.APPLICATION_JSON).content("{\"invoice\":{}}");
    }

    @Test
    @DisplayName("from Payop through Cloudflare: accepted and handed on")
    void fromPayop() throws Exception {
        mvc.perform(ipn().with(from("10.204.3.17"))
                        .header("X-Forwarded-For", "6.6.6.6, " + PAYOP + ", 172.70.1.1")
                        .header("CF-Connecting-IP", PAYOP))
                .andExpect(status().isOk());
        verify(callbacks).handle(IPN);
    }

    @Test
    @DisplayName("Payop's address forged in the headers, sent straight to the host: refused, kept for staff")
    void forged() throws Exception {
        mvc.perform(ipn().with(from("10.204.3.17"))
                        .header("X-Forwarded-For", PAYOP + ", 198.51.100.7")
                        .header("CF-Connecting-IP", PAYOP))
                .andExpect(status().isForbidden());
        verify(callbacks, never()).handle(any());
        verify(callbacks).recordRejected(IPN, "198.51.100.7");
    }

    @Test
    @DisplayName("from anywhere else, or from nowhere we can tell: refused")
    void elsewhere() throws Exception {
        mvc.perform(ipn().with(from("203.0.113.9")).header("X-Forwarded-For", PAYOP)).andExpect(status().isForbidden());
        mvc.perform(ipn().with(from("10.204.3.17"))).andExpect(status().isForbidden());
        verify(callbacks, never()).handle(any());
    }

    @Test
    @DisplayName("Payop's API unreachable while confirming: 503, so Payop sends it again")
    void transientFailure() throws Exception {
        when(callbacks.handle(IPN)).thenThrow(new PayopClient.PayopException("get transaction",
                PayopClient.ErrorCode.UNAVAILABLE, 0, List.of()));
        mvc.perform(ipn().with(from("10.204.3.17")).header("X-Forwarded-For", "172.70.1.1")
                        .header("CF-Connecting-IP", PAYOP))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("from Payop but not an IPN we can read: 400")
    void unreadable() throws Exception {
        when(callbacks.parse(any())).thenReturn(Optional.empty());
        mvc.perform(ipn().with(from("10.204.3.17")).header("X-Forwarded-For", "172.70.1.1")
                        .header("CF-Connecting-IP", PAYOP))
                .andExpect(status().isBadRequest());
        verify(callbacks, never()).handle(any());
    }

    @Test
    @DisplayName("the customer endpoints are open to guests; the order is found by reference and email")
    void guests() throws Exception {
        OrderEntity order = mock(OrderEntity.class);
        when(order.getId()).thenReturn(7L);
        when(order.getCurrency()).thenReturn(Currency.EUR);
        when(order.getStatus()).thenReturn(OrderStatus.AWAITING_PAYMENT);
        when(orders.requireGuest("GFS-26-EUR00001", "buyer@example.com")).thenReturn(order);
        when(checkout.options(order, "DE")).thenReturn(new PayopCheckoutService.Options(Currency.EUR, 9000,
                List.of(new PayopCheckoutService.MethodOption(381, "Bank transfer", "bank_transfer", 407, 9407)), null));

        mvc.perform(post("/api/v1/payments/payop/options").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order\":\"GFS-26-EUR00001\",\"email\":\"buyer@example.com\",\"country\":\"DE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feeLabel").value("Payment processing fee"))
                .andExpect(jsonPath("$.methods[0].totalMinor").value(9407))
                .andExpect(jsonPath("$.methods[0].feeMinor").value(407));
        mvc.perform(get("/api/v1/payments/payop/country").header("CF-IPCountry", "de"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.country").value("DE"));
        mvc.perform(get("/api/v1/payments/payop/country").header("CF-IPCountry", "T1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.country").doesNotExist());
    }

    @Test
    @DisplayName("a start request carries no price the server would use: only a method and the total to check")
    void startNeedsTheExpectedTotal() throws Exception {
        mvc.perform(post("/api/v1/payments/payop/invoices").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order\":\"GFS-26-EUR00001\",\"email\":\"buyer@example.com\",\"methodId\":381,"
                                + "\"country\":\"DE\"}"))
                .andExpect(status().isBadRequest());
        verify(checkout, never()).start(any(), eq(381L), anyString(), eq(0L), any());
    }
}
