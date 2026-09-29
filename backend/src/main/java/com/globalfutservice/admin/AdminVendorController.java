package com.globalfutservice.admin;

import java.time.Instant;

import com.globalfutservice.fulfilment.VendorControl;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FUT Transfer as a whole: whether calls to it are paused, and resuming them.
 *
 * <p>Admin only, like every action that reaches the vendor. Resuming is a statement that
 * the API credentials have been fixed; if they have not, the next call is refused again
 * and pauses everything again.
 */
@RestController
@RequestMapping("/api/v1/admin/vendor")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin — fulfilment partner", description = "FUT Transfer controls")
public class AdminVendorController {

    private final VendorControl control;

    public AdminVendorController(VendorControl control) {
        this.control = control;
    }

    public record ControlView(boolean paused, Instant pausedAt, String reason, Instant resumedAt) {
    }

    @GetMapping("/control")
    @Operation(summary = "Whether calls to FUT Transfer are paused, and why")
    public ResponseEntity<ControlView> control() {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(view());
    }

    @PostMapping("/resume")
    @Operation(summary = "Resume calls to FUT Transfer after fixing the API credentials")
    public ResponseEntity<ControlView> resume(@CurrentAccount AccountPrincipal admin) {
        control.resume(admin.id());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(view());
    }

    private ControlView view() {
        return control.state()
                .map(s -> new ControlView(s.paused(), s.pausedAt(), s.reason(), s.resumedAt()))
                .orElse(new ControlView(false, null, null, null));
    }
}
