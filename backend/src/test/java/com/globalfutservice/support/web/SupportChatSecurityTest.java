package com.globalfutservice.support.web;

import java.util.Map;

import com.globalfutservice.config.SecurityConfig;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.JwtService;
import com.globalfutservice.support.chat.SupportChatService;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The support page's data is the signed-in owner's only; everyone else is told nothing. */
@WebMvcTest(SupportChatController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "GFS_JWT_SECRET=test-only-jwt-secret-000000000000000000000000000000",
        "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
        "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
class SupportChatSecurityTest {

    private static final String PATH = "/api/v1/orders/GFS-26-70C4DPWH/support-context";

    @Autowired
    private MockMvc mvc;

    @MockBean private SupportChatService support;
    @MockBean private JwtService jwtService;

    private static UsernamePasswordAuthenticationToken customer(long id) {
        AccountPrincipal p = new AccountPrincipal(id, "acc_" + id, null, AccountRole.CUSTOMER);
        return new UsernamePasswordAuthenticationToken(p, null, p.authorities());
    }

    @Test
    @DisplayName("nobody signed in gets nothing, and the order is never looked up")
    void anonymous() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        verify(support, never()).contextFor(anyString(), anyLong());
    }

    @Test
    @DisplayName("the owner gets the page's data, never cached")
    void owner() throws Exception {
        when(support.contextFor("GFS-26-70C4DPWH", 7L)).thenReturn(new SupportChatService.SupportContext(
                SupportChatService.Mode.COINS,
                new SupportChatService.Summary("GFS-26-70C4DPWH", "Buy Coins — 500K", "PC", "Queued", "500K",
                        null, null, null, null),
                new SupportChatService.Chat("Rahul", "rahul@example.test", null, Map.of("order-id", "GFS-26-70C4DPWH"))));

        mvc.perform(get(PATH).with(authentication(customer(7L))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.mode").value("COINS"))
                .andExpect(jsonPath("$.summary.status").value("Queued"));
        verify(support).contextFor(eq("GFS-26-70C4DPWH"), eq(7L));
    }

    @Test
    @DisplayName("another customer's order, or one that does not exist: not found, alike")
    void notTheirs() throws Exception {
        when(support.contextFor(anyString(), eq(8L))).thenThrow(new ApiExceptions.NotFoundException("No such order."));
        mvc.perform(get(PATH).with(authentication(customer(8L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No such order."));
    }
}
