package com.globalfutservice.admin;

import com.globalfutservice.catalog.ListingService;
import com.globalfutservice.config.SecurityConfig;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Listings set what customers are charged, so they are an admin's and nobody else's. */
@WebMvcTest(AdminListingController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "GFS_JWT_SECRET=test-only-jwt-secret-000000000000000000000000000000",
        "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
        "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
class AdminListingSecurityTest {

    private static final String BASE = "/api/v1/admin/listings";
    private static final String SAVE = "{\"prices\":{\"INR\":190000},\"active\":true,\"successRateBps\":9400,\"bestValue\":false}";
    private static final String ADD = "{\"sku\":\"BOOST_CHAMPS\",\"name\":\"16 wins\",\"prices\":{\"INR\":400000}}";

    @Autowired
    private MockMvc mvc;

    @MockBean private ListingService listings;
    @MockBean private JwtService jwtService;

    private static UsernamePasswordAuthenticationToken as(AccountRole role) {
        AccountPrincipal p = new AccountPrincipal(1L, "acc_staff", "t@example.test", role);
        return new UsernamePasswordAuthenticationToken(p, null, p.authorities());
    }

    @Test
    @DisplayName("an operator, a customer and nobody at all are all refused, and nothing is changed")
    void onlyAdmins() throws Exception {
        for (AccountRole role : List.of(AccountRole.OPERATOR, AccountRole.CUSTOMER)) {
            mvc.perform(get(BASE).with(authentication(as(role)))).andExpect(status().isForbidden());
            mvc.perform(put(BASE + "/BOOST_CHAMPS/WINS_11").contentType(MediaType.APPLICATION_JSON).content(SAVE)
                    .with(authentication(as(role)))).andExpect(status().isForbidden());
            mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(ADD)
                    .with(authentication(as(role)))).andExpect(status().isForbidden());
        }
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        verify(listings, never()).save(any(), anyString(), any(), anyLong());
        verify(listings, never()).add(any(), anyLong());
    }

    @Test
    @DisplayName("an admin saves a listing and adds one")
    void admin() throws Exception {
        when(listings.overview()).thenReturn(new ListingService.Overview(List.of("INR"), List.of()));
        when(listings.add(any(), eq(1L))).thenReturn("16_WINS");

        mvc.perform(put(BASE + "/boost_champs/WINS_11").contentType(MediaType.APPLICATION_JSON).content(SAVE)
                .with(authentication(as(AccountRole.ADMIN)))).andExpect(status().isOk());
        ArgumentCaptor<ListingService.Change> change = ArgumentCaptor.forClass(ListingService.Change.class);
        verify(listings).save(eq(Sku.BOOST_CHAMPS), eq("WINS_11"), change.capture(), eq(1L));
        assertThat(change.getValue().prices()).containsEntry("INR", 190000L);
        assertThat(change.getValue().successRateBps()).isEqualTo(9400);

        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(ADD)
                .with(authentication(as(AccountRole.ADMIN)))).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a service that does not exist is a 400")
    void unknownService() throws Exception {
        mvc.perform(put(BASE + "/TOURNAMENTS/X").contentType(MediaType.APPLICATION_JSON).content(SAVE)
                .with(authentication(as(AccountRole.ADMIN)))).andExpect(status().isBadRequest());
    }
}
