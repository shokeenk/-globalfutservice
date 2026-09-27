package com.globalfutservice.admin;

import com.globalfutservice.config.SecurityConfig;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.payments.ManualPaymentMethod;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.payments.RefundEntity;
import com.globalfutservice.payments.RefundService;
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
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Payments are staff's to read; totals, the export and refunds are an admin's. */
@WebMvcTest(AdminPaymentController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "GFS_JWT_SECRET=test-only-jwt-secret-000000000000000000000000000000",
        "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
        "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
class AdminPaymentSecurityTest {

    private static final String BASE = "/api/v1/admin/payments";
    private static final String REFUND = """
            {"publicRef":"GFS-26-REFUND01","method":"upi","reference":"412345678901","reason":"Could not deliver"}
            """;

    @Autowired
    private MockMvc mvc;

    @MockBean private AdminPaymentQueries queries;
    @MockBean private RefundService refunds;
    @MockBean private JwtService jwtService;

    private static UsernamePasswordAuthenticationToken as(AccountRole role) {
        AccountPrincipal p = new AccountPrincipal(1L, "acc_staff", "t@example.test", role);
        return new UsernamePasswordAuthenticationToken(p, null, p.authorities());
    }

    private static AdminPaymentQueries.Row row() {
        return new AdminPaymentQueries.Row(4L, "GFS-26-CN43SP05", "=cmd|' /C calc'!A0", "rahul07@example.test",
                "UPI", "412345678901", "9166172359@ybl", "SUCCESS", "READY_FOR_DELIVERY", 102500, "₹1,025.00", "INR",
                Instant.parse("2026-09-26T10:04:00Z"), Instant.parse("2026-09-26T10:30:00Z"), "Vinay", null, true, null);
    }

    @Test
    @DisplayName("nobody signed in, and no customer, gets any of it")
    void outsiders() throws Exception {
        for (String path : List.of(BASE, BASE + "/overview", BASE + "/export")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(authentication(as(AccountRole.CUSTOMER)))).andExpect(status().isForbidden());
        }
        mvc.perform(post(BASE + "/refunds").contentType(MediaType.APPLICATION_JSON).content(REFUND))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an operator reads payments, and gets an overview without money")
    void operatorReads() throws Exception {
        when(queries.search(any(), anyInt(), anyInt())).thenReturn(new AdminPaymentQueries.Page(List.of(row()), 1, 0, 25));
        when(queries.overview(false)).thenReturn(new AdminPaymentQueries.Overview(List.of(), List.of(), Map.of()));

        mvc.perform(get(BASE).param("status", "pending").param("method", "paypal")
                        .with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isOk());
        mvc.perform(get(BASE + "/overview").with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isOk());
        verify(queries).overview(false);
        verify(queries, never()).overview(true);
    }

    @Test
    @DisplayName("filters the page does not have are a 400")
    void badFilters() throws Exception {
        mvc.perform(get(BASE).param("status", "CANCELLED").with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE).param("method", "CARD").with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an operator can neither export nor record a refund")
    void operatorCannot() throws Exception {
        mvc.perform(get(BASE + "/export").with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/refunds").contentType(MediaType.APPLICATION_JSON).content(REFUND)
                        .with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isForbidden());
        verify(queries, never()).export(any(), anyInt());
        verify(refunds, never()).record(anyString(), any(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("an admin exports a CSV with customer-typed formulas neutralised")
    void adminExports() throws Exception {
        when(queries.export(any(), anyInt())).thenReturn(List.of(row()));
        MvcResult result = mvc.perform(get(BASE + "/export").with(authentication(as(AccountRole.ADMIN))))
                .andExpect(status().isOk()).andReturn();
        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿Submitted (IST),Order,");
        assertThat(body).contains("2026-09-26 15:34,GFS-26-CN43SP05,'=cmd|' /C calc'!A0,");
        assertThat(body).contains(",INR,1025.00,SUCCESS,");
    }

    @Test
    @DisplayName("an admin records a refund; the method is checked")
    void adminRefunds() throws Exception {
        when(refunds.record(eq("GFS-26-REFUND01"), eq(ManualPaymentMethod.UPI), anyString(), anyString(), eq(1L), eq("acc_staff")))
                .thenReturn(new RefundEntity(9L, 102500, Currency.INR, ManualPaymentMethod.UPI, "412345678901", "Could not deliver", 1L));
        mvc.perform(post(BASE + "/refunds").contentType(MediaType.APPLICATION_JSON).content(REFUND)
                        .with(authentication(as(AccountRole.ADMIN))))
                .andExpect(status().isCreated());

        mvc.perform(post(BASE + "/refunds").contentType(MediaType.APPLICATION_JSON)
                        .content(REFUND.replace("upi", "card"))
                        .with(authentication(as(AccountRole.ADMIN))))
                .andExpect(status().isBadRequest());
    }
}
