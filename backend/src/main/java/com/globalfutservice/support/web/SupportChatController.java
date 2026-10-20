package com.globalfutservice.support.web;

import com.globalfutservice.orders.web.OrderDtos;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.support.chat.SupportChatService;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The order support page's data: the order's summary and what the live chat is told.
 *
 * <p>Signed-in owners by reference; a guest order by its reference and email, as the guest
 * order lookup asks. Anyone else, and any unknown reference, gets "not found".
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Customer orders")
public class SupportChatController {

    private final SupportChatService support;

    public SupportChatController(SupportChatService support) {
        this.support = support;
    }

    @GetMapping("/{publicRef}/support-context")
    @Operation(summary = "The support page for one of the signed-in customer's orders",
            description = "The order's summary in customer words, and the allowlisted fields the live chat is "
                    + "given. Never a credential, a backup code or anything about payment.")
    public ResponseEntity<SupportChatService.SupportContext> context(
            @PathVariable String publicRef,
            @CurrentAccount AccountPrincipal principal) {
        if (principal == null) {
            throw new ApiExceptions.ForbiddenException("Please sign in.");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(support.contextFor(publicRef, principal.id()));
    }

    @PostMapping("/track/support-context")
    @Operation(summary = "The support page for a guest order, by its reference and email",
            description = "The same proof as the guest order lookup. The chat is given the order's own email "
                    + "and the same allowlisted fields; never a credential, a backup code or anything about payment.")
    public ResponseEntity<SupportChatService.SupportContext> guestContext(
            @Valid @RequestBody OrderDtos.TrackOrderRequest request) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(support.guestContextFor(request.publicRef(), request.email()));
    }
}
