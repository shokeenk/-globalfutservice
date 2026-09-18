package com.globalfutservice.admin;

import com.globalfutservice.notify.feed.CustomerFeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sending one notice to every customer.
 *
 * <p>The only notification a person writes by hand. Everything else in the feed is caused
 * by an order doing something; this is the business saying something, so it needs somebody
 * to type it and a button to send it.
 *
 * <p><b>It goes to everybody and cannot be recalled.</b> Operators can place orders and
 * move them along, but a message landing in every customer's bell at once is a different
 * kind of action, so it is restricted to ADMIN. The link is relative on purpose: an
 * announcement that can carry an arbitrary external URL is a phishing vector wearing the
 * site's own chrome.
 */
@RestController
@RequestMapping("/api/v1/admin/announcements")
@Tag(name = "Admin: announcements", description = "Send one notice to every customer")
public class AdminAnnouncementController {

    private final CustomerFeedService feed;

    public AdminAnnouncementController(CustomerFeedService feed) {
        this.feed = feed;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Send an announcement to every customer")
    public ResponseEntity<SendResponse> send(@Valid @RequestBody SendRequest request) {
        int sent = feed.broadcast(request.title().trim(),
                blankToNull(request.body()), blankToNull(request.link()));
        return ResponseEntity.ok(new SendResponse(sent));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record SendRequest(
            @NotBlank(message = "An announcement needs a title")
            @Size(max = 120, message = "Keep the title under 120 characters")
            String title,

            @Size(max = 500, message = "Keep the message under 500 characters")
            String body,

            /*
             * A path on this site, not a URL. Anything else would let an announcement send
             * every customer somewhere we do not control, from a message that looks like
             * it came from us.
             */
            @Pattern(regexp = "^(/[A-Za-z0-9\\-._~!$&'()*+,;=:@%/?#]*)?$",
                    message = "A link must be a path on this site, starting with /")
            @Size(max = 200)
            String link) {
    }

    public record SendResponse(int sent) {
    }
}
