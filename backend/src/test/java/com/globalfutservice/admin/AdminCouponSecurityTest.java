package com.globalfutservice.admin;

import java.util.List;

import com.globalfutservice.config.SecurityConfig;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.pricing.CouponDeletionEntity;
import com.globalfutservice.pricing.CouponDeletionRepository;
import com.globalfutservice.pricing.CouponRepository;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Deleting a coupon is an admin's, as creating and changing one are; the deleted list is read like the list. */
@WebMvcTest(AdminCouponController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "GFS_JWT_SECRET=test-only-jwt-secret-000000000000000000000000000000",
        "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
        "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
class AdminCouponSecurityTest {

    private static final String BASE = "/api/v1/admin/coupons";

    @Autowired
    private MockMvc mvc;

    @MockBean private CouponRepository coupons;
    @MockBean private CouponDeletion deletion;
    @MockBean private CouponDeletionRepository deletions;
    @MockBean private AccountRepository accounts;
    @MockBean private JwtService jwtService;

    private static UsernamePasswordAuthenticationToken as(AccountRole role) {
        AccountPrincipal p = new AccountPrincipal(9L, "acc_staff", "staff@example.test", role);
        return new UsernamePasswordAuthenticationToken(p, null, p.authorities());
    }

    @Test
    @DisplayName("strangers, customers and operators cannot delete a coupon, and nothing is deleted")
    void notAdmins() throws Exception {
        mvc.perform(delete(BASE + "/5")).andExpect(status().isUnauthorized());
        for (AccountRole role : List.of(AccountRole.CUSTOMER, AccountRole.OPERATOR)) {
            mvc.perform(delete(BASE + "/5").with(authentication(as(role)))).andExpect(status().isForbidden());
        }
        verify(deletion, never()).delete(anyLong(), any());
    }

    @Test
    @DisplayName("an admin deletes it, under their own name, and is told which way it went")
    void admin() throws Exception {
        when(deletion.delete(5L, 9L)).thenReturn(
                new CouponDeletion.Deleted(5L, "SAVE10", CouponDeletionEntity.Outcome.HIDDEN));
        mvc.perform(delete(BASE + "/5").with(authentication(as(AccountRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SAVE10"))
                .andExpect(jsonPath("$.outcome").value("HIDDEN"));
        verify(deletion).delete(5L, 9L);
    }

    @Test
    @DisplayName("the deleted list is read as the list is -- operators and admins -- and never by customers")
    void deletedList() throws Exception {
        when(deletions.findAllByOrderByDeletedAtDesc(any())).thenReturn(Page.empty());
        mvc.perform(get(BASE + "/deleted")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/deleted").with(authentication(as(AccountRole.CUSTOMER)))).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/deleted").with(authentication(as(AccountRole.OPERATOR)))).andExpect(status().isOk());
        mvc.perform(get(BASE + "/deleted").with(authentication(as(AccountRole.ADMIN)))).andExpect(status().isOk());
    }
}
