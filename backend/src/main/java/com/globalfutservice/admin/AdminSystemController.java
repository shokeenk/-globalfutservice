package com.globalfutservice.admin;

import com.globalfutservice.config.MemoryReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * How the server itself is doing, for admins: memory, so a climb towards the instance's limit
 * is seen before Render restarts the service. Figures only -- never a JVM argument, a property
 * or the environment, which can hold secrets.
 */
@RestController
@RequestMapping("/api/v1/admin/system")
@Tag(name = "Admin — System", description = "Server memory, for admins")
public class AdminSystemController {

    private final MemoryReport memory;

    public AdminSystemController(MemoryReport memory) {
        this.memory = memory;
    }

    @GetMapping("/memory")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Memory now: heap, class metadata, JIT code, direct buffers, threads, and the container's")
    public ResponseEntity<MemoryReport.Snapshot> memory() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(memory.now());
    }
}
