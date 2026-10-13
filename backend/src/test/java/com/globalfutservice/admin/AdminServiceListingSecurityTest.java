package com.globalfutservice.admin;

import com.globalfutservice.catalog.CatalogService;
import com.globalfutservice.catalog.listing.ServiceListingImageEntity;
import com.globalfutservice.catalog.listing.ServiceListingService;
import com.globalfutservice.catalog.web.CatalogController;
import com.globalfutservice.config.SecurityConfig;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Objectives and Tournaments listings are an admin's to change: nobody signed in, no
 * customer and no operator adds, edits, duplicates, deletes or gives one artwork. The card
 * artwork itself is public, served as a PNG that can be cached for ever.
 */
@WebMvcTest({AdminServiceListingController.class, CatalogController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "GFS_JWT_SECRET=test-only-jwt-secret-000000000000000000000000000000",
        "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
        "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
class AdminServiceListingSecurityTest {

    private static final String BASE = "/api/v1/admin/service-listings";
    private static final String FORM = """
            {"sku":"BOOST_OBJECTIVES","title":"Claire Hutton Objective","pricingType":"FIXED","active":true,
             "prices":{"INR":120000}}""";

    @Autowired
    private MockMvc mvc;

    @MockBean private ServiceListingService listings;
    @MockBean private CatalogService catalog;
    @MockBean private JwtService jwtService;

    private static UsernamePasswordAuthenticationToken as(AccountRole role) {
        AccountPrincipal p = new AccountPrincipal(1L, "acc_staff", "t@example.test", role);
        return new UsernamePasswordAuthenticationToken(p, null, p.authorities());
    }

    private static ServiceListingService.AdminView view() {
        return new ServiceListingService.AdminView(7L, "BOOST_OBJECTIVES", "LISTING_7", "Claire Hutton Objective",
                null, null, null, null, null, null, null, null, "FIXED", 50, true, null, true, 1, Map.of(),
                "Vinay", Instant.now(), "Vinay", Instant.now(), List.of());
    }

    @BeforeEach
    void setUp() {
        when(listings.one(anyLong())).thenReturn(view());
        when(listings.create(any(), any())).thenReturn(7L);
        when(listings.duplicate(anyLong(), any())).thenReturn(8L);
    }

    private static List<RequestBuilder> everyChange() {
        return List.of(
                post(BASE).contentType(MediaType.APPLICATION_JSON).content(FORM),
                put(BASE + "/7").contentType(MediaType.APPLICATION_JSON).content(FORM),
                post(BASE + "/7/duplicate"),
                delete(BASE + "/7"),
                multipart(BASE + "/7/image").file(new MockMultipartFile("file", "a.png", "image/png", new byte[] {1})),
                delete(BASE + "/7/image"));
    }

    @Test
    @DisplayName("nobody signed in reaches any of it")
    void anonymous() throws Exception {
        mvc.perform(get(BASE + "/7")).andExpect(status().isUnauthorized());
        for (RequestBuilder request : everyChange()) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        verifyNothingChanged();
    }

    @Test
    @DisplayName("a customer or an operator is refused every read and every change")
    void notAdmins() throws Exception {
        for (AccountRole role : new AccountRole[] {AccountRole.CUSTOMER, AccountRole.OPERATOR}) {
            mvc.perform(get(BASE + "/7").with(authentication(as(role)))).andExpect(status().isForbidden());
            mvc.perform(get(BASE + "/7/references").with(authentication(as(role)))).andExpect(status().isForbidden());
            for (RequestBuilder request : List.of(
                    post(BASE).contentType(MediaType.APPLICATION_JSON).content(FORM).with(authentication(as(role))),
                    put(BASE + "/7").contentType(MediaType.APPLICATION_JSON).content(FORM)
                            .with(authentication(as(role))),
                    post(BASE + "/7/duplicate").with(authentication(as(role))),
                    delete(BASE + "/7").with(authentication(as(role))),
                    multipart(BASE + "/7/image").file(new MockMultipartFile("file", "a.png", "image/png",
                            new byte[] {1})).with(authentication(as(role))),
                    delete(BASE + "/7/image").with(authentication(as(role))))) {
                mvc.perform(request).andExpect(status().isForbidden());
            }
        }
        verifyNothingChanged();
    }

    @Test
    @DisplayName("an admin adds, edits, duplicates, deletes and uploads, each recorded as them")
    void admin() throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(FORM)
                .with(authentication(as(AccountRole.ADMIN)))).andExpect(status().isCreated());
        mvc.perform(put(BASE + "/7").contentType(MediaType.APPLICATION_JSON).content(FORM)
                .with(authentication(as(AccountRole.ADMIN)))).andExpect(status().isOk());
        mvc.perform(post(BASE + "/7/duplicate").with(authentication(as(AccountRole.ADMIN))))
                .andExpect(status().isCreated());
        mvc.perform(delete(BASE + "/7").with(authentication(as(AccountRole.ADMIN)))).andExpect(status().isNoContent());
        mvc.perform(multipart(BASE + "/7/image").file(new MockMultipartFile("file", "a.png", "image/png",
                new byte[] {1})).with(authentication(as(AccountRole.ADMIN)))).andExpect(status().isOk());
        verify(listings).create(any(), eq(1L));
        verify(listings).update(eq(7L), any(), eq(1L));
        verify(listings).duplicate(7L, 1L);
        verify(listings).delete(7L, 1L);
        verify(listings).setImage(eq(7L), any(), eq(1L));
    }

    @Test
    @DisplayName("card artwork is public: a PNG, never sniffed, cached for ever")
    void artworkIsPublic() throws Exception {
        ServiceListingImageEntity image = mock(ServiceListingImageEntity.class);
        when(image.getBytes()).thenReturn(new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        when(listings.imageBytes(12L)).thenReturn(Optional.of(image));
        mvc.perform(get("/api/v1/catalog/listing-images/12"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("immutable")));
        mvc.perform(get("/api/v1/catalog/listing-images/13")).andExpect(status().isNotFound());
    }

    private void verifyNothingChanged() {
        verify(listings, never()).create(any(), any());
        verify(listings, never()).update(anyLong(), any(), any());
        verify(listings, never()).duplicate(anyLong(), any());
        verify(listings, never()).delete(anyLong(), any());
        verify(listings, never()).setImage(anyLong(), any(), any());
        verify(listings, never()).removeImage(anyLong(), any());
    }
}
