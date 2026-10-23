package com.globalfutservice.support.chat;

import com.globalfutservice.config.SecurityConfig;
import com.globalfutservice.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * tawk.to's public ids, served at runtime from the API's environment: to anyone, and never
 * the Secure Mode key.
 */
class ChatConfigControllerTest {

    private static final String SECRETS = "GFS_JWT_SECRET=test-only-jwt-secret-000000000000000000000000000000";

    @Nested
    @WebMvcTest(ChatConfigController.class)
    @Import(SecurityConfig.class)
    @TestPropertySource(properties = {
            SECRETS,
            "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
            "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "GFS_TAWK_SECURE_KEY=never-sent-secure-key",
            "GFS_TAWK_PROPERTY_ID=prop123abc",
            "GFS_TAWK_EMBED_WIDGET_ID=embed01",
            "GFS_TAWK_FLOATING_WIDGET_ID=1k5test",
    })
    class Configured {

        @Autowired private MockMvc mvc;
        @MockBean private JwtService jwtService;

        @Test
        @DisplayName("anyone, signed in or not, gets the three ids; never the Secure Mode key")
        void ids() throws Exception {
            mvc.perform(get("/api/v1/chat/config"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.propertyId").value("prop123abc"))
                    .andExpect(jsonPath("$.embedWidgetId").value("embed01"))
                    .andExpect(jsonPath("$.floatingWidgetId").value("1k5test"))
                    .andExpect(content().string(not(containsString("never-sent-secure-key"))))
                    .andExpect(content().string(not(containsString("secure"))));
        }
    }

    @Nested
    @WebMvcTest(ChatConfigController.class)
    @Import(SecurityConfig.class)
    @TestPropertySource(properties = {
            SECRETS,
            "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
            "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "GFS_TAWK_PROPERTY_ID=prop123abc",
            "GFS_TAWK_EMBED_WIDGET_ID=bad/../id",
    })
    class NotConfigured {

        @Autowired private MockMvc mvc;
        @MockBean private JwtService jwtService;

        @Test
        @DisplayName("unset or malformed ids come back as null, so the storefront shows no chat for them")
        void nulls() throws Exception {
            mvc.perform(get("/api/v1/chat/config"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.propertyId").value("prop123abc"))
                    .andExpect(jsonPath("$.embedWidgetId").doesNotExist())
                    .andExpect(jsonPath("$.floatingWidgetId").doesNotExist());
        }
    }
}
