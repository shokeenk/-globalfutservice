package com.globalfutservice.admin;

import com.globalfutservice.config.SecurityConfig;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.fulfilment.SupplierFulfilmentService;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.orders.web.OrderMapper;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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
 * Who can reach the Orders page's new endpoints, and what the export contains.
 *
 * <p>Search, overview and the sign-in reminder are queue work, so an operator has them.
 * The export is a file of amounts, so it is an admin's.
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
class AdminOrderPageSecurityTest {

    private static final String BASE = "/api/v1/admin/orders";

    @Autowired
    private MockMvc mvc;

    @MockBean private OrderRepository orders;
    @MockBean private OrderService orderService;
    @MockBean private OrderMapper mapper;
    @MockBean private CredentialVaultService vaultService;
    @MockBean private SupplierFulfilmentService supplierFulfilment;
    @MockBean private AdminOrderQueries queries;
    @MockBean private JwtService jwtService;

    private static UsernamePasswordAuthenticationToken as(AccountRole role) {
        AccountPrincipal p = new AccountPrincipal(1L, "acc_test", "t@example.test", role);
        return new UsernamePasswordAuthenticationToken(p, null, p.authorities());
    }

    private static AdminOrderViews.Row row(String name) {
        return new AdminOrderViews.Row("GFS-26-CN43SP05", "AWAITING_PAYMENT", Sku.COACHING.name(),
                "FUT Classes — Single session · 1 hour", "SINGLE_SESSION", BigDecimal.ONE,
                "PLAYSTATION", "SCHEDULED_SESSION", false, false, name, "rahul07@example.test",
                "SUBMITTED", "UPI", "412345678901", "Rahul_07", 102500, "₹1,025.00", "INR",
                Instant.parse("2026-09-26T10:04:00Z"), null, List.of());
    }

    @BeforeEach
    void stub() {
        when(queries.search(any(), anyInt(), anyInt()))
                .thenReturn(new AdminOrderViews.Page(List.of(row("Rahul")), 1, 0, 25));
        when(queries.overview()).thenReturn(new AdminOrderViews.Overview(
                List.of(new AdminOrderViews.StatusCount("COACHING", "AWAITING_PAYMENT", 1)),
                2, 3, 1, 2, 4, 5, 6));
        when(queries.export(any(), anyInt())).thenReturn(List.of(row("=HYPERLINK(\"http://evil\")")));
        OrderEntity order = org.mockito.Mockito.mock(OrderEntity.class);
        when(orderService.requireAny("GFS-26-CN43SP05")).thenReturn(order);
        when(orderService.remindCredentials(any(), any(), any()))
                .thenReturn(Instant.parse("2026-09-27T06:00:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/search", "/overview", "/export"})
    @DisplayName("nobody signed in gets nothing")
    void anonymous(String path) throws Exception {
        mvc.perform(get(BASE + path)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/search", "/overview", "/export"})
    @DisplayName("a customer is refused")
    void customer(String path) throws Exception {
        mvc.perform(get(BASE + path).with(authentication(as(AccountRole.CUSTOMER))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an operator can search, with the page's filters passed through parsed")
    void operatorSearches() throws Exception {
        mvc.perform(get(BASE + "/search")
                        .param("service", "BOOSTING").param("status", "DELIVERED,COMPLETED")
                        .param("from", "2026-09-01").param("to", "2026-09-27")
                        .param("search", "rahul").param("page", "2").param("size", "500")
                        .with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].publicRef").value("GFS-26-CN43SP05"))
                .andExpect(jsonPath("$.items[0].customerName").value("Rahul"))
                .andExpect(jsonPath("$.items[0].paymentState").value("SUBMITTED"));

        ArgumentCaptor<AdminOrderFilter> filter = ArgumentCaptor.forClass(AdminOrderFilter.class);
        // Size is capped at 100 whatever the page asks for.
        verify(queries).search(filter.capture(), eq(2), eq(100));
        assertThat(filter.getValue().skus()).containsExactlyInAnyOrder(Sku.BOOST_CHAMPS, Sku.BOOST_RIVALS);
        assertThat(filter.getValue().search()).isEqualTo("rahul");
    }

    @Test
    @DisplayName("a filter the page does not have is a 400, not an empty table")
    void badFilter() throws Exception {
        mvc.perform(get(BASE + "/search").param("service", "REWARDS")
                        .with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an operator gets the counts, which carry no money")
    void operatorOverview() throws Exception {
        mvc.perform(get(BASE + "/overview").with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentsToCheck").value(2))
                .andExpect(jsonPath("$.signInsToWork").value(3))
                .andExpect(jsonPath("$.deliveredToday").value(4))
                .andExpect(jsonPath("$.counts[0].count").value(1))
                .andExpect(jsonPath("$.revenueLast30dMinor").doesNotExist());
    }

    @Test
    @DisplayName("an operator cannot export, and the export query never runs for them")
    void operatorCannotExport() throws Exception {
        mvc.perform(get(BASE + "/export").with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isForbidden());
        verify(queries, never()).export(any(), anyInt());
    }

    @Test
    @DisplayName("an admin exports a CSV attachment with the customer's formula neutralised")
    void adminExports() throws Exception {
        MvcResult result = mvc.perform(get(BASE + "/export").param("status", "AWAITING_PAYMENT")
                        .with(authentication(as(AccountRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("attachment; filename=\"orders-")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();

        assertThat(result.getResponse().getContentType()).startsWith("text/csv");
        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿Reference,Placed (IST),Status,");
        assertThat(body).contains("GFS-26-CN43SP05,2026-09-26 15:34,AWAITING_PAYMENT,");
        assertThat(body).contains(",1025.00,UPI,412345678901,SUBMITTED,");
        assertThat(body).contains("\"'=HYPERLINK(\"\"http://evil\"\")\"");
    }

    @Test
    @DisplayName("releasing to the partner puts who did it on the timeline: the public id, never an email")
    void releaseNamesWhoDidIt() throws Exception {
        OrderEntity order = org.mockito.Mockito.mock(OrderEntity.class);
        when(order.getId()).thenReturn(7L);
        when(order.getSku()).thenReturn(Sku.TRADING_SERVICE);
        when(order.getStatus()).thenReturn(com.globalfutservice.domain.orders.OrderStatus.READY_FOR_DELIVERY);
        when(orderService.requireAny("GFS-26-RELEASE1")).thenReturn(order);
        when(vaultService.status(7L)).thenReturn(
                new com.globalfutservice.credentials.web.CredentialDtos.VaultStatus(true, false, null, 0));
        when(supplierFulfilment.approveAndDispatch(order, 1L)).thenReturn(new SupplierFulfilmentService.Release(
                SupplierFulfilmentService.Result.SUBMITTED, "SUP-1", "Sent to the fulfilment partner as SUP-1."));
        when(orderService.transition(any(), any(), any(), any(), any(), any())).thenReturn(order);
        // As an access token builds it: no email.
        AccountPrincipal fromToken = new AccountPrincipal(1L, "acc_test", null, AccountRole.OPERATOR);

        mvc.perform(post(BASE + "/GFS-26-RELEASE1/approve-fulfilment").with(authentication(
                        new UsernamePasswordAuthenticationToken(fromToken, null, fromToken.authorities()))))
                .andExpect(status().isOk());

        verify(orderService).transition(eq(order), eq(com.globalfutservice.domain.orders.OrderStatus.IN_PROGRESS),
                eq(com.globalfutservice.domain.orders.Actor.OPERATOR), eq(1L), eq("acc_test"),
                eq("Released to fulfilment partner as SUP-1"));
    }

    @Test
    @DisplayName("an operator can send the sign-in reminder; a customer cannot")
    void reminder() throws Exception {
        mvc.perform(post(BASE + "/GFS-26-CN43SP05/credentials/remind")
                        .with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentAt").value("2026-09-27T06:00:00Z"));
        verify(orderService).remindCredentials(any(), eq(1L), eq("acc_test"));

        mvc.perform(post(BASE + "/GFS-26-CN43SP05/credentials/remind")
                        .with(authentication(as(AccountRole.CUSTOMER))))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/GFS-26-CN43SP05/credentials/remind"))
                .andExpect(status().isUnauthorized());
    }
}
