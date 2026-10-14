package com.globalfutservice.orders.web;

import com.globalfutservice.config.SecurityConfig;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.credentials.web.CredentialDtos;
import com.globalfutservice.fulfilment.SupplierFulfilmentService;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.JwtService;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who can be given the partner's tracking link: only the signed-in owner, on their own order.
 * Another customer gets exactly the not-found a missing order gets, and the guest lookup --
 * reference and email -- is answered without the link.
 */
@WebMvcTest(OrderController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "GFS_JWT_SECRET=test-only-jwt-secret-000000000000000000000000000000",
        "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
        "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
class OrderLinkVisibilityTest {

    private static final String REF = "GFS-26-LINKVIS1";

    @Autowired
    private MockMvc mvc;

    @MockBean private OrderService orderService;
    @MockBean private OrderRepository orders;
    @MockBean private OrderMapper mapper;
    @MockBean private SupplierFulfilmentService supplierFulfilment;
    @MockBean private AccountRepository accounts;
    @MockBean private CredentialVaultService vaultService;
    @MockBean private JwtService jwtService;

    private final OrderEntity order = mock(OrderEntity.class);

    private static UsernamePasswordAuthenticationToken customer(long id) {
        AccountPrincipal p = new AccountPrincipal(id, "acc_" + id, "c" + id + "@example.test", AccountRole.CUSTOMER);
        return new UsernamePasswordAuthenticationToken(p, null, p.authorities());
    }

    @BeforeEach
    void setUp() {
        when(order.getId()).thenReturn(7L);
        when(orderService.requireOwned(REF, 1L)).thenReturn(order);
        when(orderService.requireOwned(eq(REF), eq(2L)))
                .thenThrow(new ApiExceptions.NotFoundException("No such order."));
        when(orderService.requireOwned(eq("GFS-26-NOSUCH01"), any()))
                .thenThrow(new ApiExceptions.NotFoundException("No such order."));
        when(orderService.timeline(7L)).thenReturn(List.of());
        when(vaultService.status(7L)).thenReturn(new CredentialDtos.VaultStatus(true, false, null, 0));
    }

    @Test
    @DisplayName("the owner's own order is mapped with the link")
    void owner() throws Exception {
        mvc.perform(get("/api/v1/orders/" + REF).with(authentication(customer(1L)))).andExpect(status().isOk());
        verify(mapper).toOwnerResponse(eq(order), anyList(), anyBoolean());
        verify(mapper, never()).toResponse(any(), anyList(), anyBoolean());
    }

    @Test
    @DisplayName("another customer: the same not-found as an order that does not exist, and nothing mapped")
    void otherCustomer() throws Exception {
        String theirs = mvc.perform(get("/api/v1/orders/" + REF).with(authentication(customer(2L))))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String missing = mvc.perform(get("/api/v1/orders/GFS-26-NOSUCH01").with(authentication(customer(2L))))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

        // The same answer, but for when it was made and its trace id.
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode a = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(theirs);
        com.fasterxml.jackson.databind.node.ObjectNode b = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(missing);
        a.remove(List.of("timestamp", "traceId"));
        b.remove(List.of("timestamp", "traceId"));
        assertThat(a).isEqualTo(b);
        assertThat(theirs).doesNotContain("transferstatus");
        verify(mapper, never()).toOwnerResponse(any(), anyList(), anyBoolean());
    }

    @Test
    @DisplayName("the guest lookup by reference and email is answered without the link")
    void guestLookup() throws Exception {
        when(orderService.requireGuest(anyString(), anyString())).thenReturn(order);

        mvc.perform(post("/api/v1/orders/track").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicRef\":\"" + REF + "\",\"email\":\"c1@example.test\"}"))
                .andExpect(status().isOk());

        verify(mapper).toResponse(eq(order), anyList(), anyBoolean());
        verify(mapper, never()).toOwnerResponse(any(), anyList(), anyBoolean());
    }
}
