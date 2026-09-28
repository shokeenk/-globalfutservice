package com.globalfutservice.admin;

import com.globalfutservice.config.SecurityConfig;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.identity.AccountRole;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Customers are staff's to look up; what they spent and their names are an admin's to see
 * and change.
 */
@WebMvcTest(AdminCustomerController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "GFS_JWT_SECRET=test-only-jwt-secret-000000000000000000000000000000",
        "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
        "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
class AdminCustomerSecurityTest {

    private static final String BASE = "/api/v1/admin/customers";

    @Autowired
    private MockMvc mvc;

    @MockBean private AdminCustomerQueries queries;
    @MockBean private AccountRepository accounts;
    @MockBean private JwtService jwtService;

    private static UsernamePasswordAuthenticationToken as(AccountRole role) {
        AccountPrincipal p = new AccountPrincipal(1L, "acc_staff", "t@example.test", role);
        return new UsernamePasswordAuthenticationToken(p, null, p.authorities());
    }

    private static AdminCustomerQueries.Row row() {
        return new AdminCustomerQueries.Row("a-acc_rahul", "ACCOUNT", "Rahul Sharma", "rahul07@example.test",
                "rahul_07", "PLAYSTATION", 6, null, Instant.parse("2026-09-26T10:04:00Z"),
                Instant.parse("2026-08-12T09:44:00Z"), "ACTIVE", true);
    }

    @Test
    @DisplayName("nobody signed in, and no customer, gets any of it")
    void outsiders() throws Exception {
        for (String path : List.of(BASE, BASE + "/overview", BASE + "/a-acc_rahul")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(authentication(as(AccountRole.CUSTOMER)))).andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("an operator's list is asked for without money; an admin's with it")
    void spentForAdminsOnly() throws Exception {
        when(queries.search(any(), any(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(new AdminCustomerQueries.Page(List.of(row()), 1, 0, 25));

        mvc.perform(get(BASE).param("filter", "with").with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isOk());
        verify(queries).search(any(), eq(AdminCustomerQueries.Filter.WITH_ORDERS), eq(0), eq(25), eq(false));

        mvc.perform(get(BASE).with(authentication(as(AccountRole.ADMIN)))).andExpect(status().isOk());
        verify(queries).search(any(), eq(AdminCustomerQueries.Filter.ALL), eq(0), eq(25), eq(true));
    }

    @Test
    @DisplayName("the same goes for one customer's details")
    void detailMoney() throws Exception {
        when(queries.detail(eq("a-acc_rahul"), anyBoolean()))
                .thenReturn(Optional.of(new AdminCustomerQueries.Detail(row(), List.of())));
        mvc.perform(get(BASE + "/a-acc_rahul").with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isOk());
        verify(queries).detail("a-acc_rahul", false);

        when(queries.detail(eq("g-nothing"), anyBoolean())).thenReturn(Optional.empty());
        mvc.perform(get(BASE + "/g-nothing").with(authentication(as(AccountRole.ADMIN))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an unknown filter is a 400")
    void badFilter() throws Exception {
        mvc.perform(get(BASE).param("filter", "vip").with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("only an admin renames, only an account, and only a customer's")
    void rename() throws Exception {
        String body = "{\"name\":\"Rahul Sharma\"}";

        mvc.perform(patch(BASE + "/a-acc_rahul").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isForbidden());

        mvc.perform(patch(BASE + "/g-GFS-26-ABC").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(authentication(as(AccountRole.ADMIN))))
                .andExpect(status().isConflict());

        AccountEntity staff = mock(AccountEntity.class);
        when(staff.getRole()).thenReturn(AccountRole.OPERATOR);
        when(accounts.findByPublicId("acc_staff")).thenReturn(Optional.of(staff));
        mvc.perform(patch(BASE + "/a-acc_staff").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(authentication(as(AccountRole.ADMIN))))
                .andExpect(status().isNotFound());
        verify(staff, never()).setDisplayName(any());

        AccountEntity customer = mock(AccountEntity.class);
        when(customer.getRole()).thenReturn(AccountRole.CUSTOMER);
        when(accounts.findByPublicId("acc_rahul")).thenReturn(Optional.of(customer));
        when(queries.detail("a-acc_rahul", true))
                .thenReturn(Optional.of(new AdminCustomerQueries.Detail(row(), List.of())));
        mvc.perform(patch(BASE + "/a-acc_rahul").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  Rahul Sharma  \"}")
                        .with(authentication(as(AccountRole.ADMIN))))
                .andExpect(status().isOk());
        verify(customer).setDisplayName("Rahul Sharma");
    }
}
