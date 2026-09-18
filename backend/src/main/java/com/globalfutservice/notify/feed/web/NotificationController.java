package com.globalfutservice.notify.feed.web;

import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.notify.feed.CustomerFeedService;
import com.globalfutservice.notify.feed.CustomerNotificationEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * The customer's own notification feed.
 *
 * <p>Everything here is scoped to the account making the request — there is no id in the
 * path that could be somebody else's, and the one endpoint that takes an id checks the row
 * belongs to the caller before touching it.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "What has happened to this customer's orders")
public class NotificationController {

    private final CustomerFeedService feed;

    public NotificationController(CustomerFeedService feed) {
        this.feed = feed;
    }

    /** The feed, newest first, with the unread count the bell badge shows. */
    @GetMapping
    @Operation(summary = "This customer's notifications")
    public ResponseEntity<FeedResponse> feed(@CurrentAccount AccountPrincipal principal,
                                             @RequestParam(defaultValue = "20") int limit) {
        List<Item> items = feed.feed(principal.id(), limit).stream()
                .map(NotificationController::toItem)
                .toList();
        return ResponseEntity.ok(new FeedResponse(items, feed.unreadCount(principal.id())));
    }

    /** Opening the bell marks what is in it as read. */
    @PostMapping("/read")
    @Operation(summary = "Mark every notification as read")
    public ResponseEntity<Void> readAll(@CurrentAccount AccountPrincipal principal) {
        feed.markAllRead(principal.id());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Mark one notification as read")
    public ResponseEntity<Void> read(@CurrentAccount AccountPrincipal principal,
                                     @PathVariable Long id) {
        feed.markRead(principal.id(), id);
        return ResponseEntity.noContent().build();
    }

    private static Item toItem(CustomerNotificationEntity row) {
        return new Item(row.getId(), row.getKind(), row.getTitle(), row.getBody(),
                row.getLink(), row.getOrderRef(), row.getCreatedAt(), row.getReadAt() != null);
    }

    public record FeedResponse(List<Item> items, long unread) {
    }

    public record Item(Long id, String kind, String title, String body, String link,
                       String orderRef, Instant createdAt, boolean read) {
    }
}
