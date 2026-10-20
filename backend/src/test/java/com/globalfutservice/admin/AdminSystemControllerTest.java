package com.globalfutservice.admin;

import com.globalfutservice.config.MemoryReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.assertj.core.api.Assertions.assertThat;

/** Server memory is for admins only, never cached, and figures only. */
class AdminSystemControllerTest {

    @Test
    @DisplayName("admins only: the method demands ADMIN, on top of the console's own role")
    void adminOnly() throws NoSuchMethodException {
        PreAuthorize rule = AdminSystemController.class.getMethod("memory").getAnnotation(PreAuthorize.class);
        assertThat(rule).isNotNull();
        assertThat(rule.value()).isEqualTo("hasRole('ADMIN')");
    }

    @Test
    @DisplayName("the figures now, never stored by a cache")
    void figures() {
        var response = new AdminSystemController(new MemoryReport()).memory();
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().heapMaxMb()).isPositive();
    }
}
