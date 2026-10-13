package com.globalfutservice.admin;

import java.util.List;

import com.globalfutservice.config.SecurityConfig;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.orders.result.OrderResultService;
import com.globalfutservice.orders.result.ResultDtos;
import com.globalfutservice.orders.web.OrderMapper;
import com.globalfutservice.orders.web.OrderResultController;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.JwtService;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who can do what with a Champions result: staff read it, record the wins and deliver; the
 * method, Paid, Issued and the proof are an admin's; the customer's choice is the owner's
 * alone; and nobody else gets anything.
 */
@WebMvcTest({AdminOrderResultController.class, OrderResultController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "GFS_JWT_SECRET=test-only-jwt-secret-000000000000000000000000000000",
        "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
        "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
class AdminOrderResultSecurityTest {

    private static final String REF = "GFS-26-592VBQ2Z";
    private static final String BASE = "/api/v1/admin/orders/" + REF + "/result";
    private static final String PAID = "{\"method\":\"UPI\",\"reference\":\"412345678901\",\"confirmed\":true}";

    @Autowired
    private MockMvc mvc;

    @MockBean private OrderService orders;
    @MockBean private OrderResultService results;
    @MockBean private OrderMapper mapper;
    @MockBean private CredentialVaultService vault;
    @MockBean private JwtService jwtService;

    private final OrderEntity order = mock(OrderEntity.class);

    private static UsernamePasswordAuthenticationToken as(AccountRole role) {
        AccountPrincipal p = new AccountPrincipal(1L, "acc_staff", "t@example.test", role);
        return new UsernamePasswordAuthenticationToken(p, null, p.authorities());
    }

    private static ResultDtos.AdminView view() {
        return new ResultDtos.AdminView(true, null, "CHAMPS_WINS", "INR", 15, 9, List.of(), 400000, "₹4,000.00",
                380000, "₹3,800.00", 0, null);
    }

    @BeforeEach
    void setUp() {
        when(orders.requireAny(REF)).thenReturn(order);
        when(results.adminView(any())).thenReturn(view());
        when(vault.status(any())).thenReturn(mock(com.globalfutservice.credentials.web.CredentialDtos.VaultStatus.class));
    }

    @Test
    @DisplayName("nobody signed in, and no customer, reaches the admin result")
    void outsiders() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE).with(authentication(as(AccountRole.CUSTOMER)))).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/paid").with(authentication(as(AccountRole.CUSTOMER)))
                .contentType(MediaType.APPLICATION_JSON).content(PAID)).andExpect(status().isForbidden());
        verify(results, never()).markPaid(any(), any(), any());
    }

    @Test
    @DisplayName("an operator reads it, corrects the wins and delivers")
    void operator() throws Exception {
        mvc.perform(get(BASE).with(authentication(as(AccountRole.OPERATOR)))).andExpect(status().isOk());
        mvc.perform(put(BASE).with(authentication(as(AccountRole.OPERATOR))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"achievedLevel\":13,\"belowLowest\":false}")).andExpect(status().isOk());
        ArgumentCaptor<OrderResultService.Staff> who = ArgumentCaptor.forClass(OrderResultService.Staff.class);
        verify(results).update(eq(order), any(), who.capture());
        assertThat(who.getValue().admin()).as("an operator is never treated as an admin").isFalse();

        when(results.deliver(any(), any(), any(), any())).thenReturn(order);
        mvc.perform(post(BASE + "/deliver").with(authentication(as(AccountRole.OPERATOR)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"result\":{\"achievedLevel\":14,\"belowLowest\":false}}")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Paid, Issued and the proof are refused to an operator before anything happens")
    void operatorCannotSettle() throws Exception {
        mvc.perform(post(BASE + "/paid").with(authentication(as(AccountRole.OPERATOR)))
                .contentType(MediaType.APPLICATION_JSON).content(PAID)).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/issue").with(authentication(as(AccountRole.OPERATOR)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}")).andExpect(status().isForbidden());
        mvc.perform(multipart(BASE + "/proof").file(new MockMultipartFile("file", "a.png", "image/png", new byte[] {1}))
                .with(authentication(as(AccountRole.OPERATOR)))).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/proof").with(authentication(as(AccountRole.OPERATOR)))).andExpect(status().isForbidden());
        verify(results, never()).markPaid(any(), any(), any());
        verify(results, never()).issue(any(), any(), any());
        verify(results, never()).attachProof(any(), any(), any());
    }

    @Test
    @DisplayName("an admin settles")
    void admin() throws Exception {
        mvc.perform(post(BASE + "/paid").with(authentication(as(AccountRole.ADMIN)))
                .contentType(MediaType.APPLICATION_JSON).content(PAID)).andExpect(status().isOk());
        mvc.perform(post(BASE + "/issue").with(authentication(as(AccountRole.ADMIN)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}")).andExpect(status().isOk());
        ArgumentCaptor<OrderResultService.Staff> who = ArgumentCaptor.forClass(OrderResultService.Staff.class);
        verify(results).markPaid(eq(order), any(), who.capture());
        assertThat(who.getValue().admin()).isTrue();
    }

    @Test
    @DisplayName("the customer's choice: their own order only, signed in")
    void customerChoice() throws Exception {
        String choice = "/api/v1/orders/" + REF + "/result/choice";
        mvc.perform(post(choice).contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"REFUND\"}"))
                .andExpect(status().isUnauthorized());

        when(orders.requireOwned(REF, 1L)).thenReturn(order);
        mvc.perform(post(choice).with(authentication(as(AccountRole.CUSTOMER))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"REFUND\"}")).andExpect(status().isOk());
        verify(results).choose(order, "REFUND", 1L);

        when(orders.requireOwned("GFS-26-NOTMINE1", 1L)).thenThrow(new ApiExceptions.NotFoundException("No such order."));
        mvc.perform(post("/api/v1/orders/GFS-26-NOTMINE1/result/choice").with(authentication(as(AccountRole.CUSTOMER)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"REFUND\"}"))
                .andExpect(status().isNotFound());
        verify(results, never()).choose(any(), anyString(), eq(2L));
    }
}
