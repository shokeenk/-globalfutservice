package com.globalfutservice.admin;

import java.util.List;

import com.globalfutservice.config.SecurityConfig;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.popups.PopupImageStore;
import com.globalfutservice.popups.PopupPublicService;
import com.globalfutservice.popups.PopupService;
import com.globalfutservice.popups.web.PopupController;
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
import org.springframework.test.web.servlet.RequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Only an admin manages popups; the storefront's three endpoints are open to anyone. */
@WebMvcTest({AdminPopupController.class, PopupController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "GFS_JWT_SECRET=test-only-jwt-secret-000000000000000000000000000000",
        "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
        "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
class AdminPopupSecurityTest {

    private static final String BASE = "/api/v1/admin/popups";

    @Autowired
    private MockMvc mvc;

    @MockBean private PopupService popups;
    @MockBean private PopupPublicService live;
    @MockBean private PopupImageStore images;
    @MockBean private AccountRepository accounts;
    @MockBean private JwtService jwtService;

    private static UsernamePasswordAuthenticationToken as(AccountRole role) {
        AccountPrincipal p = new AccountPrincipal(1L, "acc_staff", "t@example.test", role);
        return new UsernamePasswordAuthenticationToken(p, null, p.authorities());
    }

    private static List<RequestBuilder> everyAdminCall(AccountRole role) {
        var auth = authentication(as(role));
        String body = "{\"name\":\"x\"}";
        return List.of(
                get(BASE).with(auth), get(BASE + "/options").with(auth), get(BASE + "/pop_1").with(auth),
                get(BASE + "/pop_1/changes").with(auth),
                post(BASE).with(auth).contentType(MediaType.APPLICATION_JSON).content(body),
                put(BASE + "/pop_1").with(auth).contentType(MediaType.APPLICATION_JSON).content(body),
                post(BASE + "/pop_1/duplicate").with(auth), delete(BASE + "/pop_1").with(auth),
                post(BASE + "/pop_1/publish").with(auth), post(BASE + "/pop_1/unpublish").with(auth),
                put(BASE + "/pop_1/enabled").with(auth).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"),
                multipart(BASE + "/pop_1/images/desktop").file(new MockMultipartFile("file", new byte[] {1})).with(auth),
                delete(BASE + "/pop_1/images/mobile").with(auth));
    }

    @Test
    @DisplayName("customers and operators cannot list, create, change, publish, upload or delete popups")
    void notForOperators() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        for (AccountRole role : List.of(AccountRole.CUSTOMER, AccountRole.OPERATOR)) {
            for (RequestBuilder call : everyAdminCall(role)) {
                mvc.perform(call).andExpect(status().isForbidden());
            }
        }
        verifyNoInteractions(popups);
    }

    @Test
    @DisplayName("an admin can")
    void admins() throws Exception {
        when(popups.list()).thenReturn(List.of());
        mvc.perform(get(BASE).with(authentication(as(AccountRole.ADMIN)))).andExpect(status().isOk());
        mvc.perform(post(BASE + "/pop_1/publish").with(authentication(as(AccountRole.ADMIN))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the storefront's list and counters need no sign-in, and the list is cached briefly")
    void publicEndpoints() throws Exception {
        when(live.forPage(any(), any())).thenReturn(List.of());
        mvc.perform(get("/api/v1/popups").param("page", "HOME").param("path", "/"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=30, public"));
        mvc.perform(post("/api/v1/popups/pop_1/events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"event\":\"VIEW\"}")).andExpect(status().isNoContent());
    }
}
