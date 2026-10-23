package com.globalfutservice.popups.web;

import java.io.IOException;
import java.util.List;

import com.globalfutservice.popups.PopupImageStore;
import com.globalfutservice.popups.PopupPublicService;
import com.globalfutservice.popups.PopupPublicService.Event;
import com.globalfutservice.popups.PopupPublicService.PublicPopup;
import com.globalfutservice.popups.PopupTypes.Page;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The storefront's side of popups: which to show, their images, and the counters. */
@RestController
@RequestMapping("/api/v1/popups")
@Tag(name = "Popups")
public class PopupController {

    private final PopupPublicService popups;
    private final PopupImageStore images;

    public PopupController(PopupPublicService popups, PopupImageStore images) {
        this.popups = popups;
        this.images = images;
    }

    public record EventRequest(Event event) {
    }

    @GetMapping
    @Operation(summary = "The published, switched-on, in-schedule popups for a page")
    public ResponseEntity<List<PublicPopup>> forPage(@RequestParam(required = false) String page,
                                                     @RequestParam(required = false) String path) {
        Page key = null;
        if (page != null && !page.isBlank()) {
            try {
                key = Page.valueOf(page.trim());
            } catch (IllegalArgumentException e) {
                key = null;
            }
        }
        String p = path != null && path.length() <= 200 ? path : null;
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofSeconds(30)).cachePublic())
                .body(popups.forPage(key, p));
    }

    /**
     * One image. The URL carries the id and the start of the SHA-256: the bytes behind it
     * never change, so it is cached for a year.
     *
     * <p>Read whole first, then written: no database connection is held while a slow or
     * stalled browser takes the bytes. See {@link PopupImageStore}.
     */
    @GetMapping("/images/{id}/{file}")
    @Operation(summary = "A popup image")
    public void image(@PathVariable long id, @PathVariable String file, HttpServletResponse response)
            throws IOException {
        PopupImageStore.ImageRow row = images.find(id)
                .filter(r -> file.equals(r.version() + "." + r.extension()))
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such image."));
        response.setContentType(row.contentType());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable");
        response.setHeader(HttpHeaders.ETAG, "\"" + row.sha256() + "\"");
        response.setHeader("X-Content-Type-Options", "nosniff");
        byte[] bytes = images.read(id);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    @PostMapping("/{id}/events")
    @Operation(summary = "Count a view, close or button click. No personal data.")
    public ResponseEntity<Void> event(@PathVariable String id, @RequestBody EventRequest request,
                                      HttpServletRequest http) {
        if (request != null && request.event() != null && id.length() <= 40) {
            popups.record(id, request.event(), http.getRemoteAddr());
        }
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
