package com.globalfutservice.admin;

import com.globalfutservice.config.SecurityConfig;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.JwtService;
import com.globalfutservice.support.SupportService;
import com.globalfutservice.support.SupportTicketEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Support is staff work: operators and admins answer; customers and strangers get nothing. */
@WebMvcTest(AdminSupportController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "GFS_JWT_SECRET=test-only-jwt-secret-000000000000000000000000000000",
        "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
        "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
class AdminSupportSecurityTest {

    private static final String BASE = "/api/v1/admin/support";

    @Autowired
    private MockMvc mvc;

    @MockBean private NamedParameterJdbcTemplate jdbc;
    @MockBean private SupportService support;
    @MockBean private AdminCustomerQueries customers;
    @MockBean private AccountRepository accounts;
    @MockBean private JwtService jwtService;

    private static UsernamePasswordAuthenticationToken as(AccountRole role) {
        AccountPrincipal p = new AccountPrincipal(1L, "acc_staff", "vinay@example.test", role);
        return new UsernamePasswordAuthenticationToken(p, null, p.authorities());
    }

    @BeforeEach
    void stub() {
        SupportTicketEntity ticket = new SupportTicketEntity("TKT-AB12CD34", null, null, "b@example.test", "Hi", "Hi");
        ReflectionTestUtils.setField(ticket, "id", 5L);
        when(support.require("TKT-AB12CD34")).thenReturn(ticket);
        when(support.thread(any(), anyBoolean())).thenReturn(List.of());
        when(support.link(anyString())).thenReturn("https://example.test/support/tickets/TKT-AB12CD34?key=k");
    }

    @Test
    @DisplayName("nobody signed in, and no customer, can read or answer tickets")
    void outsiders() throws Exception {
        mvc.perform(get(BASE + "/tickets")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/tickets/TKT-AB12CD34").with(authentication(as(AccountRole.CUSTOMER))))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/tickets/TKT-AB12CD34/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\",\"note\":false}").with(authentication(as(AccountRole.CUSTOMER))))
                .andExpect(status().isForbidden());
        verify(support, never()).staffWrite(any(), anyString(), anyBoolean(), anyLong(), anyString());
    }

    @Test
    @DisplayName("an operator reads a ticket with its notes, and replies or notes under their own name")
    void operator() throws Exception {
        mvc.perform(get(BASE + "/tickets/TKT-AB12CD34").with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerLink").exists());
        verify(support).thread(any(), eq(true));

        mvc.perform(post(BASE + "/tickets/TKT-AB12CD34/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Checked with the partner\",\"note\":true}")
                        .with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isOk());
        verify(support).staffWrite(any(), eq("Checked with the partner"), eq(true), eq(1L), eq("vinay@example.test"));
    }

    @Test
    @DisplayName("an unknown status tab is a 400")
    void badTab() throws Exception {
        mvc.perform(get(BASE + "/tickets").param("status", "pending").with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isBadRequest());
    }
}
