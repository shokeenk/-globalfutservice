package com.globalfutservice.admin;

import java.util.List;

import com.globalfutservice.config.SecurityConfig;
import com.globalfutservice.fulfilment.VendorCallLog;
import com.globalfutservice.fulfilment.VendorOrderActions;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderService;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every action on an order at the vendor is an admin's; an operator cannot reach one. */
@WebMvcTest(AdminVendorOrderController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "GFS_JWT_SECRET=test-only-jwt-secret-000000000000000000000000000000",
        "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
        "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
class AdminVendorOrderSecurityTest {

    private static final String BASE = "/api/v1/admin/orders/GFS-26-SECURE01/vendor/";
    /** Every action this controller has. */
    private static final List<String> ACTIONS = List.of("send-sign-in", "resume", "stop", "mark-finished", "retry",
            "link", "resolve");

    @Autowired
    private MockMvc mvc;

    @MockBean private VendorOrderActions actions;
    @MockBean private OrderService orderService;
    @MockBean private VendorCallLog calls;
    @MockBean private com.globalfutservice.identity.AccountRepository accounts;
    @MockBean private JwtService jwtService;

    private static UsernamePasswordAuthenticationToken as(AccountRole role) {
        AccountPrincipal p = new AccountPrincipal(1L, "acc_staff", "staff@example.test", role);
        return new UsernamePasswordAuthenticationToken(p, null, p.authorities());
    }

    @Test
    @DisplayName("strangers, customers and operators reach none of them")
    void notAdmins() throws Exception {
        for (String action : ACTIONS) {
            mvc.perform(post(BASE + action)).andExpect(status().isUnauthorized());
            mvc.perform(post(BASE + action).with(authentication(as(AccountRole.CUSTOMER))))
                    .andExpect(status().isForbidden());
            mvc.perform(post(BASE + action).with(authentication(as(AccountRole.OPERATOR))))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get(BASE.substring(0, BASE.length() - 1))).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE.substring(0, BASE.length() - 1)).with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(actions);
    }

    @Test
    @DisplayName("an admin acts under their own name; what was not sent comes back as a 409 with the reason")
    void admin() throws Exception {
        OrderEntity order = mock(OrderEntity.class);
        when(orderService.requireAny("GFS-26-SECURE01")).thenReturn(order);
        when(actions.sendCorrectedSignIn(any(), any())).thenReturn(new VendorOrderActions.Result(
                VendorOrderActions.Status.NOT_SENT, "The customer has not entered new details yet."));

        mvc.perform(post(BASE + "send-sign-in").with(authentication(as(AccountRole.ADMIN))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The customer has not entered new details yet."));
        verify(actions).sendCorrectedSignIn(order, new VendorOrderActions.Admin(1L, "staff@example.test", "acc_staff"));
    }

    @Test
    @DisplayName("the audit names the admin from their account, since the access token carries no email")
    void labelFromAccount() throws Exception {
        OrderEntity order = mock(OrderEntity.class);
        when(orderService.requireAny("GFS-26-SECURE01")).thenReturn(order);
        com.globalfutservice.identity.AccountEntity account = mock(com.globalfutservice.identity.AccountEntity.class);
        when(account.getEmail()).thenReturn("owner@example.test");
        when(accounts.findById(1L)).thenReturn(java.util.Optional.of(account));
        when(actions.resume(any(), any())).thenReturn(new VendorOrderActions.Result(VendorOrderActions.Status.DONE, "ok"));
        AccountPrincipal fromToken = new AccountPrincipal(1L, "acc_staff", null, AccountRole.ADMIN);

        mvc.perform(post(BASE + "resume").with(authentication(
                        new UsernamePasswordAuthenticationToken(fromToken, null, fromToken.authorities()))))
                .andExpect(status().isOk());
        verify(actions).resume(order, new VendorOrderActions.Admin(1L, "owner@example.test", "acc_staff"));
    }

    @Test
    @DisplayName("what the admin typed reaches the action: the confirmation, the partner's id, the note")
    void bodies() throws Exception {
        OrderEntity order = mock(OrderEntity.class);
        when(orderService.requireAny("GFS-26-SECURE01")).thenReturn(order);
        VendorOrderActions.Result ok = new VendorOrderActions.Result(VendorOrderActions.Status.DONE, "Done.");
        when(actions.allowResend(any(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(ok);
        when(actions.link(any(), any(), any())).thenReturn(ok);
        when(actions.resolve(any(), any(), any())).thenReturn(ok);
        VendorOrderActions.Admin admin = new VendorOrderActions.Admin(1L, "staff@example.test", "acc_staff");

        mvc.perform(post(BASE + "retry").with(authentication(as(AccountRole.ADMIN)))
                        .contentType("application/json").content("{\"confirmedAbsent\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
        verify(actions).allowResend(order, admin, true);

        mvc.perform(post(BASE + "retry").with(authentication(as(AccountRole.ADMIN))))
                .andExpect(status().isOk());
        verify(actions).allowResend(order, admin, false);

        mvc.perform(post(BASE + "link").with(authentication(as(AccountRole.ADMIN)))
                        .contentType("application/json").content("{\"vendorOrderId\":\"vid-1\"}"))
                .andExpect(status().isOk());
        verify(actions).link(order, admin, "vid-1");

        mvc.perform(post(BASE + "resolve").with(authentication(as(AccountRole.ADMIN)))
                        .contentType("application/json").content("{\"note\":\"Refunded in full\"}"))
                .andExpect(status().isOk());
        verify(actions).resolve(order, admin, "Refunded in full");
    }

    @Test
    @DisplayName("a refusal or a lost answer from the partner comes back as a 502 with the reason")
    void partnerRefusal() throws Exception {
        when(orderService.requireAny("GFS-26-SECURE01")).thenReturn(mock(OrderEntity.class));
        when(actions.stop(any(), any())).thenReturn(new VendorOrderActions.Result(
                VendorOrderActions.Status.UNCERTAIN, "Could not confirm the partner stopped it (TIMEOUT)."));

        mvc.perform(post(BASE + "stop").with(authentication(as(AccountRole.ADMIN))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("Could not confirm the partner stopped it (TIMEOUT)."));
    }
}
