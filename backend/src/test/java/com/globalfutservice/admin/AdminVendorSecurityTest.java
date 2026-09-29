package com.globalfutservice.admin;

import com.globalfutservice.config.SecurityConfig;
import com.globalfutservice.fulfilment.VendorControl;
import com.globalfutservice.identity.AccountRole;
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

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Resuming calls to the vendor is an admin's decision; an operator cannot. */
@WebMvcTest(AdminVendorController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "GFS_JWT_SECRET=test-only-jwt-secret-000000000000000000000000000000",
        "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
        "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
class AdminVendorSecurityTest {

    @Autowired
    private MockMvc mvc;

    @MockBean private VendorControl control;
    @MockBean private JwtService jwtService;

    private static UsernamePasswordAuthenticationToken as(AccountRole role) {
        AccountPrincipal p = new AccountPrincipal(1L, "acc_staff", "staff@example.test", role);
        return new UsernamePasswordAuthenticationToken(p, null, p.authorities());
    }

    @Test
    @DisplayName("strangers, customers and operators can neither see nor resume")
    void notAdmins() throws Exception {
        mvc.perform(post("/api/v1/admin/vendor/resume")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/vendor/resume").with(authentication(as(AccountRole.CUSTOMER))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/vendor/resume").with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/vendor/control").with(authentication(as(AccountRole.OPERATOR))))
                .andExpect(status().isForbidden());
        verify(control, never()).resume(anyLong());
    }

    @Test
    @DisplayName("an admin resumes, under their own id")
    void admin() throws Exception {
        mvc.perform(post("/api/v1/admin/vendor/resume").with(authentication(as(AccountRole.ADMIN))))
                .andExpect(status().isOk());
        verify(control).resume(1L);
    }
}
