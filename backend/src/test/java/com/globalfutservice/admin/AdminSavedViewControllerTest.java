package com.globalfutservice.admin;

import com.globalfutservice.config.SecurityConfig;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Saved views belong to the person who saved them, and hold only the page's own filters. */
@WebMvcTest(AdminSavedViewController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "GFS_JWT_SECRET=test-only-jwt-secret-000000000000000000000000000000",
        "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
        "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
class AdminSavedViewControllerTest {

    private static final String BASE = "/api/v1/admin/saved-views";

    @Autowired
    private MockMvc mvc;

    @MockBean private SavedViewRepository views;
    @MockBean private JwtService jwtService;

    private static UsernamePasswordAuthenticationToken as(long id, AccountRole role) {
        AccountPrincipal p = new AccountPrincipal(id, "acc_" + id, id + "@example.test", role);
        return new UsernamePasswordAuthenticationToken(p, null, p.authorities());
    }

    private static SavedViewEntity saved(long id, long owner, String name, String filters) {
        SavedViewEntity view = new SavedViewEntity(owner, "orders", name, filters);
        ReflectionTestUtils.setField(view, "id", id);
        return view;
    }

    @Test
    @DisplayName("signed-out and customer requests are refused")
    void auth() throws Exception {
        mvc.perform(get(BASE).param("page", "orders")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE).param("page", "orders").with(authentication(as(9, AccountRole.CUSTOMER))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an operator lists their own views, filters and all")
    void lists() throws Exception {
        when(views.findByAccountIdAndPageOrderByNameAsc(5L, "orders")).thenReturn(List.of(
                saved(1, 5, "Coaching this week", "{\"service\":\"COACHING\",\"from\":\"2026-09-21\"}")));

        mvc.perform(get(BASE).param("page", "orders").with(authentication(as(5, AccountRole.OPERATOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Coaching this week"))
                .andExpect(jsonPath("$[0].filters.service").value("COACHING"));
    }

    @Test
    @DisplayName("saves known filters for the signed-in person, dropping blanks")
    void saves() throws Exception {
        when(views.saveAndFlush(any())).thenAnswer(call -> {
            SavedViewEntity view = call.getArgument(0);
            ReflectionTestUtils.setField(view, "id", 11L);
            return view;
        });

        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"page":"orders","name":" Disputed PS ",
                                 "filters":{"status":"DISPUTED","platform":"PLAYSTATION","search":""}}
                                """)
                        .with(authentication(as(5, AccountRole.OPERATOR))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.name").value("Disputed PS"))
                .andExpect(jsonPath("$.filters.search").doesNotExist());

        ArgumentCaptor<SavedViewEntity> stored = ArgumentCaptor.forClass(SavedViewEntity.class);
        verify(views).saveAndFlush(stored.capture());
        assertThat(stored.getValue().getAccountId()).isEqualTo(5L);
        assertThat(stored.getValue().getFilters()).isEqualTo("{\"platform\":\"PLAYSTATION\",\"status\":\"DISPUTED\"}");
    }

    @Test
    @DisplayName("a key the page does not have is refused")
    void unknownKey() throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"page\":\"orders\",\"name\":\"x\",\"filters\":{\"sql\":\"1=1\"}}")
                        .with(authentication(as(5, AccountRole.OPERATOR))))
                .andExpect(status().isBadRequest());
        verify(views, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a twenty-first view, or a second with the same name, is refused")
    void limits() throws Exception {
        when(views.countByAccountIdAndPage(5L, "orders")).thenReturn(20L);
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"page\":\"orders\",\"name\":\"One more\",\"filters\":{}}")
                        .with(authentication(as(5, AccountRole.OPERATOR))))
                .andExpect(status().isConflict());

        when(views.countByAccountIdAndPage(5L, "orders")).thenReturn(3L);
        when(views.existsByAccountIdAndPageAndNameIgnoreCase(anyLong(), anyString(), anyString())).thenReturn(true);
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"page\":\"orders\",\"name\":\"disputed\",\"filters\":{}}")
                        .with(authentication(as(5, AccountRole.OPERATOR))))
                .andExpect(status().isConflict());
        verify(views, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("somebody else's view cannot be deleted: to them it does not exist")
    void deleteOwnOnly() throws Exception {
        when(views.findByIdAndAccountId(1L, 6L)).thenReturn(Optional.empty());
        mvc.perform(delete(BASE + "/1").with(authentication(as(6, AccountRole.ADMIN))))
                .andExpect(status().isNotFound());
        verify(views, never()).delete(any());

        SavedViewEntity mine = saved(1, 5, "Mine", "{}");
        when(views.findByIdAndAccountId(1L, 5L)).thenReturn(Optional.of(mine));
        mvc.perform(delete(BASE + "/1").with(authentication(as(5, AccountRole.OPERATOR))))
                .andExpect(status().isNoContent());
        verify(views).delete(mine);
    }
}
