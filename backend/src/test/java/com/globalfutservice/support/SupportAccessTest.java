package com.globalfutservice.support;

import com.globalfutservice.config.SecurityConfig;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.JwtService;
import com.globalfutservice.support.web.SupportController;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
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

/**
 * Who can read a ticket, and what they see: its own account, or the key from our email;
 * messages, never notes; and "no such ticket" for everybody else.
 */
@WebMvcTest(SupportController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "GFS_JWT_SECRET=test-only-jwt-secret-000000000000000000000000000000",
        "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
        "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
class SupportAccessTest {

    private static final String REF = "TKT-AB12CD34";

    @Autowired
    private MockMvc mvc;

    @MockBean private SupportTicketRepository tickets;
    @MockBean private SupportService support;
    @MockBean private JwtService jwtService;

    private SupportTicketEntity ticket;

    private static UsernamePasswordAuthenticationToken customer(long id) {
        AccountPrincipal p = new AccountPrincipal(id, "acc_" + id, id + "@example.test", AccountRole.CUSTOMER);
        return new UsernamePasswordAuthenticationToken(p, null, p.authorities());
    }

    @BeforeEach
    void setUp() {
        ticket = new SupportTicketEntity(REF, 9L, null, "rahul07@example.test", "Coins not received", "Hi");
        ReflectionTestUtils.setField(ticket, "id", 5L);
        when(tickets.findByPublicRef(REF)).thenReturn(Optional.of(ticket));
        when(support.linkKeyMatches(REF, "right-key")).thenReturn(true);
        SupportMessageEntity reply = new SupportMessageEntity(5L, "STAFF", "MESSAGE", "On the way.", 2L, "vinay@example.test");
        when(support.thread(ticket, false)).thenReturn(List.of(reply));
    }

    @Test
    @DisplayName("a guest with the emailed key reads the messages, with staff shown as Support")
    void guestWithKey() throws Exception {
        mvc.perform(get("/api/v1/support/tickets/" + REF).param("key", "right-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].from").value("SUPPORT"))
                .andExpect(jsonPath("$.messages[0].body").value("On the way."))
                .andExpect(jsonPath("$.messages[0].authorLabel").doesNotExist());
        // Read without notes: the customer's endpoints never ask for them.
        verify(support).thread(ticket, false);
        verify(support, never()).thread(any(), eq(true));
    }

    @Test
    @DisplayName("no key, a wrong key, or somebody else's account: no such ticket")
    void strangers() throws Exception {
        mvc.perform(get("/api/v1/support/tickets/" + REF)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/support/tickets/" + REF).param("key", "wrong")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/support/tickets/" + REF).with(authentication(customer(10))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/support/tickets/TKT-NOPE0000").param("key", "right-key"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/support/tickets/" + REF + "/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isNotFound());
        verify(support, never()).customerWrite(any(), anyString());
    }

    @Test
    @DisplayName("the ticket's own account reads it signed in, without a key, and can answer")
    void owner() throws Exception {
        mvc.perform(get("/api/v1/support/tickets/" + REF).with(authentication(customer(9))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/support/tickets/" + REF + "/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Still nothing\"}").with(authentication(customer(9))))
                .andExpect(status().isOk());
        verify(support).customerWrite(ticket, "Still nothing");
    }

    @Test
    @DisplayName("a guest answers with the key")
    void guestAnswers() throws Exception {
        mvc.perform(post("/api/v1/support/tickets/" + REF + "/messages").param("key", "right-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Thanks\"}"))
                .andExpect(status().isOk());
        verify(support).customerWrite(ticket, "Thanks");
    }

    @Test
    @DisplayName("the list of one's tickets needs signing in")
    void listNeedsSignIn() throws Exception {
        mvc.perform(get("/api/v1/support/tickets")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the contact form still works for guests, and now takes a category")
    void form() throws Exception {
        when(support.openFromCustomer(any(), any(), anyString(), anyString(), anyString(), eq("PAYMENT"))).thenReturn(ticket);
        mvc.perform(post("/api/v1/support/tickets").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"buyer@example.test","subject":"Paid twice","message":"Please check",
                         "confirmedNoCredentials":true,"category":"PAYMENT"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ref").value(REF));
    }
}
