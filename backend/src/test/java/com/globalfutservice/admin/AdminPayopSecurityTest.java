package com.globalfutservice.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.globalfutservice.config.SecurityConfig;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.payments.WebhookEventRepository;
import com.globalfutservice.payments.payop.FxRateEntity;
import com.globalfutservice.payments.payop.FxRateService;
import com.globalfutservice.payments.payop.PayopCallbackService;
import com.globalfutservice.payments.payop.PayopFeeMethodEntity;
import com.globalfutservice.payments.payop.PayopFeeTableService;
import com.globalfutservice.payments.payop.PayopInvoiceRepository;
import com.globalfutservice.payments.payop.PayopMethodsService;
import com.globalfutservice.payments.payop.PayopReconciliation;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Payop administration: operators see it all; changing what customers pay is an admin's. */
@WebMvcTest(AdminPayopController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "GFS_JWT_SECRET=test-only-jwt-secret-000000000000000000000000000000",
        "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
        "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
class AdminPayopSecurityTest {

    private static final String BASE = "/api/v1/admin/payop";
    private static final String FEE_EDIT = """
            {"fixedEur":0.35,"percent":2.5,"countries":["DE"],"currencies":["EUR"],"active":true}
            """;
    private static final String RATE = """
            {"currency":"AED","rate":4.1224,"date":"2026-10-04"}
            """;

    @Autowired
    private MockMvc mvc;

    @MockBean private PayopFeeTableService fees;
    @MockBean private FxRateService fx;
    @MockBean private PayopInvoiceRepository invoices;
    @MockBean private OrderRepository orders;
    @MockBean private PayopCallbackService callbacks;
    @MockBean private PayopMethodsService methods;
    @MockBean private WebhookEventRepository webhooks;
    @MockBean private PayopReconciliation reconciliation;
    @MockBean private JwtService jwtService;

    private static UsernamePasswordAuthenticationToken as(AccountRole role) {
        AccountPrincipal p = new AccountPrincipal(1L, "acc_staff", "t@example.test", role);
        return new UsernamePasswordAuthenticationToken(p, null, p.authorities());
    }

    private static PayopFeeMethodEntity row() {
        return new PayopFeeMethodEntity(381, "Bank transfer", "bank_transfer", "Europe", new BigDecimal("0.30"),
                new BigDecimal("2.4"), List.of("DE"), List.of("EUR"), null, Instant.EPOCH);
    }

    @Test
    @DisplayName("nobody signed in, and no customer, gets any of it")
    void outsiders() throws Exception {
        for (String path : List.of(BASE, BASE + "/fees", BASE + "/fx", BASE + "/invoices", BASE + "/rejected")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(authentication(as(AccountRole.CUSTOMER)))).andExpect(status().isForbidden());
        }
        mvc.perform(post(BASE + "/invoices/1/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"txid\":\"dca59ca5-be19-470d-9494-9b76944e0241\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an operator sees the overview, fees, rates and payments, and can ask Payop about one")
    void operatorReads() throws Exception {
        when(fees.list()).thenReturn(List.of(row()));
        when(fx.recent()).thenReturn(List.<FxRateEntity>of());
        when(fx.eurTo(any())).thenReturn(Optional.empty());
        when(invoices.findById(1L)).thenReturn(Optional.empty());
        for (String path : List.of(BASE, BASE + "/fees", BASE + "/fees/audit", BASE + "/fx", BASE + "/invoices",
                BASE + "/rejected")) {
            mvc.perform(get(path).with(authentication(as(AccountRole.OPERATOR)))).andExpect(status().isOk());
        }
        mvc.perform(get(BASE).with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.merchantPaysNote").value(org.hamcrest.Matchers.containsString("merchant pays")));
        mvc.perform(post(BASE + "/invoices/1/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"txid\":\"dca59ca5-be19-470d-9494-9b76944e0241\"}")
                        .with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Re-check Payop payment: an operator can run it on an order; a customer or a stranger cannot")
    void recheck() throws Exception {
        String path = BASE + "/orders/GFS-26-EUR00001/recheck";
        mvc.perform(post(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(authentication(as(AccountRole.CUSTOMER)))).andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(reconciliation);

        com.globalfutservice.orders.OrderEntity order = org.mockito.Mockito.mock(
                com.globalfutservice.orders.OrderEntity.class);
        org.mockito.Mockito.when(order.getId()).thenReturn(7L);
        org.mockito.Mockito.when(order.getPublicRef()).thenReturn("GFS-26-EUR00001");
        org.mockito.Mockito.when(order.getStatus())
                .thenReturn(com.globalfutservice.domain.orders.OrderStatus.READY_FOR_DELIVERY);
        org.mockito.Mockito.when(orders.findByPublicRef("GFS-26-EUR00001")).thenReturn(java.util.Optional.of(order));
        org.mockito.Mockito.when(orders.findById(7L)).thenReturn(java.util.Optional.of(order));
        org.mockito.Mockito.when(reconciliation.recheckOrder(7L)).thenReturn(java.util.List.of(
                new PayopReconciliation.Checked("d024f697-ba2d-456f-910e-4d7fdfd338dd", "OPEN",
                        PayopCallbackService.Outcome.PAID)));
        mvc.perform(post(path).with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.orderStatus").value("READY_FOR_DELIVERY"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.invoices[0].outcome").value("PAID"));
        mvc.perform(post(BASE + "/orders/GFS-26-NOSUCH01/recheck").with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an operator cannot change a fee, import a sheet, enter a rate or accept a payment by hand")
    void operatorCannot() throws Exception {
        mvc.perform(put(BASE + "/fees/381").contentType(MediaType.APPLICATION_JSON).content(FEE_EDIT)
                .with(authentication(as(AccountRole.OPERATOR)))).andExpect(status().isForbidden());
        mvc.perform(multipart(BASE + "/fees/import").file(new MockMultipartFile("file", "x.xlsx",
                "application/octet-stream", new byte[] {1})).with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/fx").contentType(MediaType.APPLICATION_JSON).content(RATE)
                .with(authentication(as(AccountRole.OPERATOR)))).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/fx/refresh").with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/invoices/1/accept").contentType(MediaType.APPLICATION_JSON)
                .content("{\"txid\":\"dca59ca5-be19-470d-9494-9b76944e0241\",\"note\":\"checked\"}")
                .with(authentication(as(AccountRole.OPERATOR)))).andExpect(status().isForbidden());
        verify(fees, never()).update(anyLong(), any(), any());
        verify(fees, never()).importSheet(any(), any(), any());
        verify(fx, never()).enterAdminRate(any(), any(), any(), any());
        verify(callbacks, never()).acceptByHand(any(), any(), any());
    }

    @Test
    @DisplayName("an admin can change a fee and enter a rate, recorded against their account")
    void adminChanges() throws Exception {
        when(fees.update(anyLong(), any(), any())).thenReturn(row());
        when(fx.enterAdminRate(any(), any(), any(), any())).thenReturn(new FxRateEntity("AED",
                new BigDecimal("4.1224"), FxRateEntity.ADMIN, java.time.LocalDate.of(2026, 10, 4), Instant.EPOCH, 1L));
        mvc.perform(put(BASE + "/fees/381").contentType(MediaType.APPLICATION_JSON).content(FEE_EDIT)
                .with(authentication(as(AccountRole.ADMIN)))).andExpect(status().isOk());
        verify(fees).update(org.mockito.ArgumentMatchers.eq(381L), any(), org.mockito.ArgumentMatchers.eq(1L));
        verify(methods).refresh();
        mvc.perform(post(BASE + "/fx").contentType(MediaType.APPLICATION_JSON).content(RATE)
                .with(authentication(as(AccountRole.ADMIN)))).andExpect(status().isOk());
    }

    private static final String MANUAL = """
            {"methodId": 900001, "name": "Visa / Mastercard", "type": "cards_international",
             "fixedEur": 0.20, "percent": 3.5, "countries": ["IN"], "currencies": ["INR", "USD"]}
            """;

    @Test
    @DisplayName("pricing a method by hand and the Payop check are an admin's: an operator is refused, nothing changes")
    void manualAndCheckAdminOnly() throws Exception {
        mvc.perform(post(BASE + "/fees").contentType(MediaType.APPLICATION_JSON).content(MANUAL)
                .with(authentication(as(AccountRole.OPERATOR)))).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/live-methods?country=IN").with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/live-methods?country=IN")).andExpect(status().isUnauthorized());
        verify(fees, never()).saveManual(any(), any());
        verify(methods, never()).check(any());
    }

    @Test
    @DisplayName("an admin prices a method by hand, recorded against their account, and checks what Payop lists")
    void adminManualAndCheck() throws Exception {
        PayopFeeMethodEntity card = PayopFeeMethodEntity.manual(900001, "Visa / Mastercard", "cards_international",
                null, new BigDecimal("0.20"), new BigDecimal("3.5"), List.of("IN"), List.of("INR", "USD"), true, 1L,
                Instant.EPOCH);
        when(fees.saveManual(any(), any())).thenReturn(card);
        mvc.perform(post(BASE + "/fees").contentType(MediaType.APPLICATION_JSON).content(MANUAL)
                        .with(authentication(as(AccountRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.source")
                        .value("MANUAL"));
        verify(fees).saveManual(org.mockito.ArgumentMatchers.argThat(m -> m.methodId() == 900001L
                && m.type().equals("cards_international") && m.active()), org.mockito.ArgumentMatchers.eq(1L));
        verify(methods).refresh();

        when(methods.check("IN")).thenReturn(List.of(new PayopMethodsService.Listed(
                new com.globalfutservice.payments.payop.PayopClient.AvailableMethod(900001, "Cards",
                        "cards_international", List.of("INR"), List.of("IN")), card, true, true)));
        mvc.perform(get(BASE + "/live-methods?country=in").with(authentication(as(AccountRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].methodId")
                        .value(900001))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].card")
                        .value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].offered")
                        .value(true));
    }
}
