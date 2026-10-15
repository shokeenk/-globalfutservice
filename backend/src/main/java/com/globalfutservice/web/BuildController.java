package com.globalfutservice.web;

import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Which commit this API is running, so a deploy can be confirmed from outside without the
 * Render dashboard. Render sets RENDER_GIT_COMMIT on every deploy; only its first 7
 * characters are shown, and nothing else about the server.
 */
@RestController
@Tag(name = "Build", description = "Which commit is running")
public class BuildController {

    private final String commit;

    public BuildController() {
        this(System.getenv("RENDER_GIT_COMMIT"));
    }

    BuildController(String renderGitCommit) {
        this.commit = shortCommit(renderGitCommit);
    }

    static String shortCommit(String sha) {
        if (sha == null || !sha.matches("[0-9a-fA-F]{7,64}")) return "unknown";
        return sha.substring(0, 7).toLowerCase(java.util.Locale.ROOT);
    }

    @GetMapping("/api/v1/build")
    @Operation(summary = "The short commit id this API was deployed from")
    public ResponseEntity<Map<String, String>> build() {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(Map.of("commit", commit));
    }
}
