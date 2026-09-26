package com.globalfutservice.admin;

import com.globalfutservice.marketing.CampaignAudience;
import com.globalfutservice.marketing.CampaignDetails;
import com.globalfutservice.marketing.CampaignEntity;
import com.globalfutservice.marketing.CampaignService;
import com.globalfutservice.marketing.CampaignStats;
import com.globalfutservice.marketing.CampaignStatus;
import com.globalfutservice.marketing.CampaignType;
import com.globalfutservice.marketing.CtaPreset;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The campaign builder's API.
 *
 * <p>ADMIN only, like the rate card and for a stronger reason: an operator fulfils orders,
 * and a campaign reaches every opted-in customer at once with no way to recall it.
 *
 * <p>Note what is <b>not</b> here: no endpoint takes a recipient list, an arbitrary email
 * address, or a free-text destination URL. The audience comes from a fixed set of segments
 * that all require consent, and the CTA from a fixed set of site pages. An API that cannot
 * express "send this to an address I typed" cannot be used to send to somebody who never
 * opted in.
 */
@RestController
@RequestMapping("/api/v1/admin/campaigns")
@Tag(name = "Admin — campaigns", description = "Promotional mailing")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCampaignController {

    /**
     * A campaign's id in a path, constrained to what an id actually looks like.
     *
     * <p>Unconstrained, {@code POST /{publicId}} (edit a draft) also matched
     * {@code POST /preview} and {@code POST /drafts} whenever their own mappings declined
     * the request — a preview asked for as JSON was routed to "edit the campaign called
     * preview". Every id this application issues is {@code camp_} and 16 hex characters,
     * so the literal routes can no longer be mistaken for one.
     */
    private static final String ID = "{publicId:camp_[A-Za-z0-9]+}";

    /** Banners are decorative; a multi-megabyte one just slows every inbox down. */
    private static final long MAX_BANNER_BYTES = 2L * 1024 * 1024;
    private static final List<String> BANNER_TYPES =
            List.of("image/png", "image/jpeg", "image/gif", "image/webp");

    private final CampaignService campaigns;

    public AdminCampaignController(CampaignService campaigns) {
        this.campaigns = campaigns;
    }

    // ---- shapes ------------------------------------------------------------

    public record CampaignDto(String publicId, String title, String subject, String heading,
                              String body, String promoCode, String ctaText, String ctaPath,
                              boolean hasBanner, String audience, String audienceLabel,
                              String status, Instant scheduledAt, Instant completedAt,
                              Instant updatedAt, CampaignStats stats,
                              String type, String offerText, LocalDate offerValidUntil,
                              boolean showButton, boolean showPromoCode,
                              boolean trackingEnabled) {
    }

    /**
     * The campaign builder's first step, whole.
     *
     * <p>The limits are the reference design's: its subject counter reads out of 100 and
     * its description counter out of 500. They apply to this path only. The older editor
     * keeps its own, looser limits, so drafts written before this existed can still be
     * opened and saved there without being truncated.
     */
    public record DetailsRequest(
            @NotBlank(message = "Give the campaign a name")
            @Size(max = 120, message = "Keep the name to 120 characters") String title,
            @NotBlank(message = "An email subject is required")
            @Size(max = 100, message = "Keep the subject to 100 characters") String subject,
            @NotNull(message = "Choose a campaign type") CampaignType type,
            @NotBlank(message = "A promo title is required")
            @Size(max = 200, message = "Keep the promo title to 200 characters") String promoTitle,
            @Size(max = 40, message = "Keep the offer to 40 characters") String offerText,
            @Size(max = 40, message = "Keep the code to 40 characters") String promoCode,
            LocalDate offerValidUntil,
            @NotBlank(message = "Describe the offer")
            @Size(max = 500, message = "Keep the description to 500 characters") String description,
            boolean showButton,
            boolean showPromoCode,
            boolean trackingEnabled) {

        CampaignDetails toDetails() {
            return new CampaignDetails(title, subject, type, promoTitle, offerText, promoCode,
                    offerValidUntil, description, showButton, showPromoCode, trackingEnabled);
        }
    }

    public record CreateRequest(
            @NotBlank(message = "Give the campaign an internal name")
            @Size(max = 120) String title,
            @NotBlank(message = "A subject line is required")
            @Size(max = 200) String subject,
            @NotBlank(message = "A heading is required")
            @Size(max = 200) String heading,
            @NotBlank(message = "Write the message")
            @Size(max = 8000) String body,
            String audience) {
    }

    public record UpdateRequest(String title, String subject, String heading, String body,
                                @Size(max = 40) String promoCode, String cta, String audience) {
    }

    public record ScheduleRequest(Instant sendAt) {
    }

    public record OptionsDto(List<Option> audiences, List<Option> ctas) {
        public record Option(String value, String label, String detail) {
        }
    }

    // ---- reading -----------------------------------------------------------

    @GetMapping("/options")
    @Operation(summary = "The fixed audience segments and CTA buttons, with live counts")
    public ResponseEntity<OptionsDto> options() {
        List<OptionsDto.Option> audiences = Arrays.stream(CampaignAudience.values())
                .map(a -> new OptionsDto.Option(a.name(), a.label(),
                        campaigns.audienceSize(a) + " opted in"))
                .toList();
        List<OptionsDto.Option> ctas = Arrays.stream(CtaPreset.values())
                .map(c -> new OptionsDto.Option(c.name(), c.text(), c.path()))
                .toList();
        return ResponseEntity.ok(new OptionsDto(audiences, ctas));
    }

    @GetMapping
    @Operation(summary = "Campaigns by status — drafts, scheduled, or everything finished")
    public ResponseEntity<List<CampaignDto>> list(
            @RequestParam(defaultValue = "DRAFT") String status) {
        List<CampaignEntity> found = "FINISHED".equalsIgnoreCase(status)
                ? campaigns.finished()
                : campaigns.byStatus(parse(CampaignStatus.class, status));
        return ResponseEntity.ok(found.stream().map(this::toDto).toList());
    }

    @GetMapping("/" + ID)
    public ResponseEntity<CampaignDto> one(@PathVariable String publicId) {
        return ResponseEntity.ok(toDto(campaigns.get(publicId)));
    }

    /**
     * The campaign as a customer would see it.
     *
     * <p>Returned as HTML rather than JSON so the admin panel can drop it straight into an
     * iframe. It is the same renderer the send uses, so what is approved here is what goes
     * out.
     */
    @GetMapping(value = "/" + ID + "/preview", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "Rendered preview")
    public ResponseEntity<String> preview(@PathVariable String publicId) {
        return ResponseEntity.ok(campaigns.preview(publicId).html());
    }

    // ---- composing ---------------------------------------------------------

    @PostMapping
    @Operation(summary = "Create a draft")
    public ResponseEntity<CampaignDto> create(@Valid @RequestBody CreateRequest request,
                                              @CurrentAccount AccountPrincipal admin) {
        CampaignAudience segment = request.audience() == null || request.audience().isBlank()
                ? CampaignAudience.ALL_OPTED_IN
                : parse(CampaignAudience.class, request.audience());
        return ResponseEntity.ok(toDto(campaigns.create(request.title(), request.subject(),
                request.heading(), request.body(), segment, admin.id())));
    }

    /**
     * The builder's first step as the fields stand on screen, unsaved, as the email.
     *
     * <p>Deliberately looser than {@link DetailsRequest}: it renders while the admin is
     * still typing, so an over-long subject or an empty headline must still produce a
     * preview rather than an error. The caps here only bound the work a request can ask
     * for. Nothing is stored and nothing is tracked.
     */
    public record PreviewRequest(
            @Size(max = 1000) String subject,
            CampaignType type,
            @Size(max = 1000) String promoTitle,
            @Size(max = 200) String offerText,
            @Size(max = 200) String promoCode,
            LocalDate offerValidUntil,
            @Size(max = 4000) String description,
            boolean showButton,
            boolean showPromoCode,
            /** The draft being edited, for its banner, or null before the first save. */
            @Size(max = 64) String campaignId) {

        CampaignDetails toDetails() {
            return new CampaignDetails(null, subject, type, promoTitle, offerText, promoCode,
                    offerValidUntil, description, showButton, showPromoCode, false);
        }
    }

    @PostMapping(value = "/preview", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "Render unsaved builder fields as the email")
    public ResponseEntity<String> livePreview(@Valid @RequestBody PreviewRequest request) {
        return ResponseEntity.ok()
                // It is the admin's work in progress; nothing between here and the browser
                // should keep a copy.
                .cacheControl(CacheControl.noStore())
                .body(campaigns.previewDetails(request.toDetails(), request.campaignId()).html());
    }

    @PostMapping("/drafts")
    @Operation(summary = "Create a draft from the builder's first step")
    public ResponseEntity<CampaignDto> createDraft(@Valid @RequestBody DetailsRequest request,
                                                   @CurrentAccount AccountPrincipal admin) {
        return ResponseEntity.ok(toDto(campaigns.createDraft(request.toDetails(), admin.id())));
    }

    @PutMapping("/" + ID + "/details")
    @Operation(summary = "Replace the builder's first step on a draft")
    public ResponseEntity<CampaignDto> replaceDetails(@PathVariable String publicId,
                                                      @Valid @RequestBody DetailsRequest request) {
        return ResponseEntity.ok(toDto(campaigns.replaceDetails(publicId, request.toDetails())));
    }

    @PostMapping("/" + ID)
    @Operation(summary = "Edit a draft")
    public ResponseEntity<CampaignDto> update(@PathVariable String publicId,
                                              @Valid @RequestBody UpdateRequest request) {
        CtaPreset cta = CtaPreset.byName(request.cta()).orElse(null);
        if (request.cta() != null && !request.cta().isBlank() && cta == null) {
            throw new ApiExceptions.BadRequestException("That is not one of the CTA buttons.");
        }
        CampaignAudience segment = request.audience() == null || request.audience().isBlank()
                ? null : parse(CampaignAudience.class, request.audience());
        return ResponseEntity.ok(toDto(campaigns.update(publicId, request.title(),
                request.subject(), request.heading(), request.body(), request.promoCode(),
                cta, segment)));
    }

    @PostMapping(value = "/" + ID + "/banner", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload the campaign banner")
    public ResponseEntity<CampaignDto> banner(@PathVariable String publicId,
                                              @RequestPart("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new ApiExceptions.BadRequestException("That file is empty.");
        }
        if (file.getSize() > MAX_BANNER_BYTES) {
            throw new ApiExceptions.BadRequestException("Banners must be 2MB or smaller.");
        }
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!BANNER_TYPES.contains(type)) {
            throw new ApiExceptions.BadRequestException("Use a PNG, JPEG, GIF or WebP image.");
        }
        try {
            campaigns.setBanner(publicId, type, file.getBytes());
        } catch (IOException e) {
            throw new ApiExceptions.BadRequestException("That upload could not be read.");
        }
        return ResponseEntity.ok(toDto(campaigns.get(publicId)));
    }

    // ---- sending -----------------------------------------------------------

    @PostMapping("/" + ID + "/schedule")
    @Operation(summary = "Schedule for a future time")
    public ResponseEntity<CampaignDto> schedule(@PathVariable String publicId,
                                                @RequestBody ScheduleRequest request) {
        return ResponseEntity.ok(toDto(campaigns.schedule(publicId, request.sendAt())));
    }

    @PostMapping("/" + ID + "/send")
    @Operation(summary = "Send now",
            description = "Resolves the audience, writes a row per recipient, then sends.")
    public ResponseEntity<CampaignDto> send(@PathVariable String publicId) {
        campaigns.sendNow(publicId);
        return ResponseEntity.ok(toDto(campaigns.get(publicId)));
    }

    @PostMapping("/" + ID + "/cancel")
    @Operation(summary = "Withdraw a draft or scheduled campaign")
    public ResponseEntity<CampaignDto> cancel(@PathVariable String publicId) {
        return ResponseEntity.ok(toDto(campaigns.cancel(publicId)));
    }

    // ---- internals ---------------------------------------------------------

    private CampaignDto toDto(CampaignEntity c) {
        return new CampaignDto(c.getPublicId(), c.getTitle(), c.getSubject(), c.getHeading(),
                c.getBody(), c.getPromoCode(), c.getCtaText(), c.getCtaPath(), c.hasBanner(),
                c.getAudience().name(), c.getAudience().label(), c.getStatus().name(),
                c.getScheduledAt(), c.getCompletedAt(), c.getUpdatedAt(),
                campaigns.stats(c.getId()),
                c.getType().name(), c.getOfferText(), c.getOfferValidUntil(),
                c.getCtaPath() != null, c.isShowPromoCode(), c.isTrackingEnabled());
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiExceptions.BadRequestException("Unknown value: " + raw);
        }
    }
}
