package com.globalfutservice.marketing.web;

import com.globalfutservice.marketing.CampaignEntity;
import com.globalfutservice.marketing.CampaignService;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * The endpoints a promotional email itself reaches back into: unsubscribe, open tracking,
 * click tracking, and the banner image.
 *
 * <p>All public and unauthenticated, necessarily — they are opened from a mail client by
 * somebody who is not signed in. Each is therefore built so that being called by a
 * stranger, a bot or a security scanner costs nothing: the worst an attacker can do with a
 * guessed token is unsubscribe a random person from marketing, and the tokens are random
 * UUIDs precisely so guessing is not a strategy.
 */
@RestController
@RequestMapping("/api/v1/marketing")
@Tag(name = "Marketing", description = "Unsubscribe and campaign tracking")
public class MarketingController {

    /** A 1x1 transparent GIF. Smallest thing that is a valid image in every client. */
    private static final byte[] PIXEL = Base64.getDecoder().decode(
            "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");

    private final CampaignService campaigns;

    public MarketingController(CampaignService campaigns) {
        this.campaigns = campaigns;
    }

    public record UnsubscribeRequest(String token, String campaign) {
    }

    public record UnsubscribeResult(boolean unsubscribed, String message) {
    }

    /**
     * Withdraw promotional consent.
     *
     * <p><b>POST, not GET, and that is the whole point.</b> Corporate mail security
     * products and link-preview bots fetch every URL in an incoming message to check it is
     * safe. A GET that unsubscribes would therefore unsubscribe people who never clicked
     * anything — silently, and in exactly the accounts whose employer runs the strictest
     * scanner. The email links to a page; the page asks; this runs on the answer.
     *
     * <p>The one exception is RFC 8058 one-click, which mail clients send as a POST with a
     * form body. That is handled by {@link #oneClick}, which is safe for the same reason:
     * a scanner does not POST.
     */
    @PostMapping("/unsubscribe")
    @Operation(summary = "Unsubscribe from promotional email")
    public ResponseEntity<UnsubscribeResult> unsubscribe(@RequestBody UnsubscribeRequest request) {
        UUID token = parseToken(request.token());
        boolean known = campaigns.unsubscribe(token, blankToNull(request.campaign()));
        /*
         * The same answer either way.
         *
         * Saying "no such token" would turn this into an oracle for testing whether a
         * given token is live. There is nothing useful a caller can do with the
         * distinction, and the honest message for the person who clicked is identical.
         */
        return ResponseEntity.ok(new UnsubscribeResult(true,
                known ? "You will no longer receive promotional email from Global FUT Services."
                      : "You will no longer receive promotional email from Global FUT Services."));
    }

    /**
     * RFC 8058 one-click, sent by Gmail and Outlook's own Unsubscribe button.
     *
     * <p>The body is {@code List-Unsubscribe=One-Click}; the token travels in the query
     * string because that is the URL the header advertised.
     */
    @PostMapping(value = "/unsubscribe/one-click")
    @Operation(summary = "One-click unsubscribe (RFC 8058)")
    public ResponseEntity<String> oneClick(@RequestParam("t") String token,
                                           @RequestParam(value = "c", required = false) String campaign) {
        campaigns.unsubscribe(parseToken(token), blankToNull(campaign));
        return ResponseEntity.ok("Unsubscribed");
    }

    /**
     * Open tracking.
     *
     * <p>Always returns the pixel, whether or not the token is known — an image that 404s
     * shows a broken-image icon in the reader's mail, which is a worse outcome than losing
     * one analytics data point. Marked no-store so a caching proxy cannot swallow the
     * request and hide every subsequent open.
     */
    @GetMapping(value = "/o/{token}.gif", produces = MediaType.IMAGE_GIF_VALUE)
    @Operation(summary = "Open-tracking pixel")
    public ResponseEntity<byte[]> open(@PathVariable String token) {
        try {
            campaigns.recordOpen(UUID.fromString(token));
        } catch (RuntimeException ignored) {
            // A malformed or unknown token is not worth an error page in somebody's inbox.
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_GIF)
                .body(PIXEL);
    }

    /**
     * Click tracking, as a redirect.
     *
     * <p>An unknown token still redirects, to the storefront, rather than showing an
     * error: somebody who clicked a button in an email should arrive somewhere useful even
     * if their tracking row was purged.
     */
    @GetMapping("/c/{token}")
    @Operation(summary = "Click-tracking redirect")
    public ResponseEntity<Void> click(@PathVariable String token) {
        Optional<String> target;
        try {
            target = campaigns.recordClick(UUID.fromString(token));
        } catch (IllegalArgumentException e) {
            target = Optional.empty();
        }
        return ResponseEntity.status(302)
                .location(URI.create(target.orElse("/")))
                .build();
    }

    /**
     * The campaign banner.
     *
     * <p>Served by campaign id rather than from a file store, because that is where the
     * bytes are. Cached hard: the image for a given campaign never changes once it has
     * been sent, and every recipient's mail client will ask for it.
     */
    @GetMapping("/campaigns/{publicId}/banner")
    @Operation(summary = "Campaign banner image")
    public ResponseEntity<byte[]> banner(@PathVariable String publicId) {
        CampaignEntity c = campaigns.withBanner(publicId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No banner."));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, c.getBannerContentType())
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .body(c.getBannerBytes());
    }

    private static UUID parseToken(String raw) {
        try {
            return UUID.fromString(raw == null ? "" : raw.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiExceptions.BadRequestException("That unsubscribe link is not valid.");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
