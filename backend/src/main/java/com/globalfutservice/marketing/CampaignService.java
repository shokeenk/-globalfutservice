package com.globalfutservice.marketing;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.notify.email.TransactionalEmails;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Composing, previewing and sending promotional campaigns.
 *
 * <p>The ordering is the part worth reading: a campaign resolves its audience into rows
 * <i>first</i>, and only then starts sending. Streaming an audience query straight into a
 * mailer means a send that dies halfway cannot resume without re-sending to everybody, and
 * that the audience shifts underfoot as people opt in and out mid-run.
 *
 * <p>The committed steps live in {@link CampaignSender}, on the far side of a real bean
 * boundary, because a service calling its own {@code @Transactional} method bypasses the
 * proxy and the annotation does nothing.
 */
@Service
public class CampaignService {

    private static final Logger log = LoggerFactory.getLogger(CampaignService.class);

    /** Rows pulled per pass, so one campaign cannot exhaust memory. */
    private static final int BATCH = 100;

    /**
     * The calendar an offer's last day is counted in. The business runs on Indian time,
     * and "valid till 10 Feb" means until the end of 10 Feb there — not in UTC, where it
     * would end at 05:30 the next morning for a customer reading it in Mumbai.
     */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    private final CampaignRepository campaigns;
    private final CampaignRecipientRepository recipients;
    private final MarketingAudienceRepository audience;
    private final AccountRepository accounts;
    private final CampaignSender sender;
    private final CampaignRenderer renderer;
    private final Clock clock;
    private final AppProperties props;

    public CampaignService(CampaignRepository campaigns,
                           CampaignRecipientRepository recipients,
                           MarketingAudienceRepository audience,
                           AccountRepository accounts,
                           CampaignSender sender,
                           CampaignRenderer renderer,
                           Clock clock,
                           AppProperties props) {
        this.campaigns = campaigns;
        this.recipients = recipients;
        this.audience = audience;
        this.accounts = accounts;
        this.sender = sender;
        this.renderer = renderer;
        this.clock = clock;
        this.props = props;
    }

    // ---- composing ---------------------------------------------------------

    @Transactional
    public CampaignEntity create(String title, String subject, String heading, String body,
                                 CampaignAudience segment, Long adminId) {
        return campaigns.save(new CampaignEntity(title, subject, heading, body, segment, adminId));
    }

    /**
     * A new draft from the campaign builder's first step.
     *
     * <p>Every audience is consent-only and this does not choose one: the draft starts on
     * everyone who opted in, and the audience is picked in its own step.
     */
    @Transactional
    public CampaignEntity createDraft(CampaignDetails details, Long adminId) {
        CampaignEntity c = new CampaignEntity(details.title(), details.subject(),
                details.promoTitle(), details.description(), CampaignAudience.ALL_OPTED_IN, adminId);
        apply(c, details);
        return campaigns.save(c);
    }

    /** Replace the first step's fields on a draft, whole. */
    @Transactional
    public CampaignEntity replaceDetails(String publicId, CampaignDetails details) {
        CampaignEntity c = requireEditable(publicId);
        apply(c, details);
        c.touch();
        return c;
    }

    private void apply(CampaignEntity c, CampaignDetails d) {
        if (d.offerValidUntil() != null && d.offerValidUntil().isBefore(today())) {
            throw new ApiExceptions.BadRequestException(
                    "The offer's last day has already passed. Pick today or a later date.");
        }
        c.setTitle(d.title().trim());
        c.setSubject(d.subject().trim());
        c.setType(d.type());
        c.setHeading(d.promoTitle().trim());
        c.setBody(d.description().strip());
        c.setOfferText(blankToNull(d.offerText()));
        c.setPromoCode(blankToNull(d.promoCode()));
        c.setOfferValidUntil(d.offerValidUntil());
        c.setShowPromoCode(d.showPromoCode());
        c.setTrackingEnabled(d.trackingEnabled());
        if (d.showButton()) {
            // The label is the reference's; the destination is the type's, and always one
            // of the fixed site pages, so the button cannot point somewhere that 404s.
            c.setCtaText(CampaignType.BUTTON_TEXT);
            c.setCtaPath(d.type().buttonPath());
        } else {
            c.setCtaText(null);
            c.setCtaPath(null);
        }
    }

    /**
     * The builder's second step: the optional lines above and below the headline.
     * Replaced whole, so clearing a line removes it.
     */
    @Transactional
    public CampaignEntity replaceContent(String publicId, String kicker, String subline) {
        CampaignEntity c = requireEditable(publicId);
        c.setHeroKicker(blankToNull(kicker));
        c.setHeroSubline(blankToNull(subline));
        c.touch();
        return c;
    }

    /**
     * The builder's fourth step. Only ever one of the fixed segments, every one of which
     * is intersected with marketing consent in the only query that can resolve it.
     */
    @Transactional
    public CampaignEntity chooseAudience(String publicId, CampaignAudience segment) {
        CampaignEntity c = requireEditable(publicId);
        c.setAudience(segment);
        c.touch();
        return c;
    }

    /**
     * How much of the provider's daily allowance campaigns have already used.
     *
     * <p>A rolling 24 hours rather than a calendar day, because the provider's day may
     * not be ours and this must not under-count. Only campaign messages are counted:
     * order emails go through the same account and spend the same allowance, but nothing
     * records them, so the true remainder can be lower than this says.
     */
    @Transactional(readOnly = true)
    public SendQuota quota() {
        int cap = props.campaigns().dailyCap();
        long sent = recipients.countByStatusAndSentAtAfter("SENT",
                clock.instant().minus(Duration.ofHours(24)));
        return new SendQuota(cap, sent, (int) Math.max(0, cap - sent));
    }

    public record SendQuota(int dailyCap, long sentLast24h, int remaining) {
    }

    /**
     * Send one copy to the signed-in admin.
     *
     * @param adminAccountId the caller's own account; the address is looked up from it
     */
    @Transactional(readOnly = true)
    public String sendTest(String publicId, Long adminAccountId) {
        requireEmailEnabled();
        CampaignEntity c = require(publicId);
        String to = accounts.findById(adminAccountId)
                .map(AccountEntity::getEmail)
                .filter(e -> e != null && !e.isBlank())
                .orElseThrow(() -> new ApiExceptions.BadRequestException(
                        "Your account has no email address to send a test to."));
        try {
            sender.sendTest(to, renderer.preview(c));
        } catch (IllegalStateException e) {
            // The admin asked for this and is looking at the screen: the relay's own
            // reason is the most useful thing to show them.
            throw new ApiExceptions.BadRequestException(
                    "The test email could not be sent: " + e.getMessage());
        }
        log.info("Test copy of campaign {} sent to account {}", c.getPublicId(), adminAccountId);
        return to;
    }

    @Transactional
    public CampaignEntity update(String publicId, String title, String subject, String heading,
                                 String body, String promoCode, CtaPreset cta,
                                 CampaignAudience segment) {
        CampaignEntity c = requireEditable(publicId);
        if (title != null) c.setTitle(title);
        if (subject != null) c.setSubject(subject);
        if (heading != null) c.setHeading(heading);
        if (body != null) c.setBody(body);
        c.setPromoCode(blankToNull(promoCode));
        if (cta != null) {
            c.setCtaText(cta.text());
            c.setCtaPath(cta.path());
        } else {
            c.setCtaText(null);
            c.setCtaPath(null);
        }
        if (segment != null) c.setAudience(segment);
        c.touch();
        return c;
    }

    @Transactional
    public void setBanner(String publicId, String contentType, byte[] bytes) {
        CampaignEntity c = requireEditable(publicId);
        c.setBanner(contentType, bytes);
        c.touch();
    }

    @Transactional
    public CampaignEntity cancel(String publicId) {
        CampaignEntity c = require(publicId);
        if (c.getStatus() == CampaignStatus.SENDING) {
            // Mid-flight is the one state where stopping is not clean: some customers
            // already have it. Refused rather than half-honoured.
            throw new ApiExceptions.BadRequestException(
                    "This campaign is already going out and cannot be cancelled.");
        }
        if (c.getStatus().terminal()) {
            throw new ApiExceptions.BadRequestException("This campaign has already finished.");
        }
        c.setStatus(CampaignStatus.CANCELLED);
        c.touch();
        return c;
    }

    // ---- reading -----------------------------------------------------------

    @Transactional(readOnly = true)
    public CampaignEntity get(String publicId) {
        return require(publicId);
    }

    @Transactional(readOnly = true)
    public List<CampaignEntity> byStatus(CampaignStatus status) {
        return campaigns.findByStatusOrderByUpdatedAtDesc(status);
    }

    /** Sent, cancelled and failed together — the "what happened" list. */
    @Transactional(readOnly = true)
    public List<CampaignEntity> finished() {
        return campaigns.findByStatusInOrderByUpdatedAtDesc(
                List.of(CampaignStatus.SENT, CampaignStatus.SENDING,
                        CampaignStatus.CANCELLED, CampaignStatus.FAILED));
    }

    @Transactional(readOnly = true)
    public CampaignStats stats(Long campaignId) {
        CampaignStats s = recipients.statsFor(campaignId);
        return (s == null ? CampaignStats.empty() : s)
                .withUnsubscribed(accounts.countByMarketingOptOutCampaignId(campaignId));
    }

    // ---- audience ----------------------------------------------------------

    /**
     * Who would receive this, right now.
     *
     * <p>Live, not stored. It moves as people opt in and out, and a stale number beside a
     * Send button is how somebody sends to a list they think is bigger than it is.
     */
    @Transactional(readOnly = true)
    public List<AccountEntity> resolve(CampaignAudience segment) {
        List<String> skuNames = segment.skus();
        if (skuNames.isEmpty()) {
            return audience.optedIn();
        }
        return audience.optedInWhoBought(skuNames.stream().map(Sku::valueOf).toList());
    }

    @Transactional(readOnly = true)
    public int audienceSize(CampaignAudience segment) {
        return resolve(segment).size();
    }

    // ---- preview -----------------------------------------------------------

    @Transactional(readOnly = true)
    public TransactionalEmails.Rendered preview(String publicId) {
        return renderer.preview(require(publicId));
    }

    /**
     * The builder's live preview of fields not yet saved.
     *
     * @param publicId the draft being edited, whose banner the preview should show, or
     *                 null before the first save
     */
    @Transactional(readOnly = true)
    public TransactionalEmails.Rendered previewDetails(CampaignDetails details, String publicId) {
        return previewDetails(details, null, null, publicId);
    }

    /** As above, with the second step's lines above and below the headline. */
    @Transactional(readOnly = true)
    public TransactionalEmails.Rendered previewDetails(CampaignDetails details, String kicker,
                                                       String subline, String publicId) {
        String banner = publicId == null || publicId.isBlank() ? null
                : campaigns.findByPublicId(publicId).map(renderer::bannerUrl).orElse(null);
        return renderer.previewOf(details, kicker, subline, banner);
    }

    // ---- sending -----------------------------------------------------------

    @Transactional
    public CampaignEntity schedule(String publicId, Instant when) {
        requireEmailEnabled();
        CampaignEntity c = requireEditable(publicId);
        if (when == null || !when.isAfter(clock.instant())) {
            throw new ApiExceptions.BadRequestException("Pick a time in the future.");
        }
        requireSendable(c);
        LocalDate sendDay = when.atZone(BUSINESS_ZONE).toLocalDate();
        if (c.getOfferValidUntil() != null && sendDay.isAfter(c.getOfferValidUntil())) {
            // Refused rather than allowed: a campaign that lands after its own offer has
            // ended tells every recipient about a code that no longer works.
            throw new ApiExceptions.BadRequestException(
                    "This would go out after the offer ends on "
                            + DAY.format(c.getOfferValidUntil())
                            + ". Pick an earlier time or change the offer's last day.");
        }
        c.setScheduledAt(when);
        c.setStatus(CampaignStatus.SCHEDULED);
        c.touch();
        log.info("Campaign {} scheduled for {}", c.getPublicId(), when);
        return c;
    }

    /**
     * Queue a campaign to go out now, and return at once.
     *
     * <p>It does not send. It makes the campaign due this minute and leaves the sending to
     * {@link CampaignScheduleJob}, which picks it up within about a minute. The send used
     * to run here, on the request thread, and the storefront's nginx gives an API request
     * 60 seconds ({@code proxy_read_timeout} in {@code frontend/nginx.conf.template}): a
     * campaign that took longer showed the admin a 504 while the backend carried on
     * sending, and a retry was then refused because the campaign was no longer a draft.
     * Queued, the request is a single UPDATE and no proxy can cut a send off halfway.
     *
     * <p>A campaign cancelled in the meantime is simply not claimed: the job's conditional
     * claim only takes a campaign that is still SCHEDULED.
     */
    @Transactional
    public CampaignEntity sendNow(String publicId) {
        requireEmailEnabled();
        CampaignEntity c = require(publicId);
        if (c.getStatus() != CampaignStatus.DRAFT && c.getStatus() != CampaignStatus.SCHEDULED) {
            throw new ApiExceptions.BadRequestException(
                    "Only a draft or scheduled campaign can be sent.");
        }
        requireSendable(c);
        c.setScheduledAt(clock.instant());
        c.setStatus(CampaignStatus.SCHEDULED);
        c.touch();
        log.info("Campaign {} queued to send now", c.getPublicId());
        return c;
    }

    /**
     * Try again for the people a finished campaign did not reach, and return at once.
     *
     * <p>A recipient the relay refuses is marked FAILED, and the send loop only ever takes
     * PENDING rows, so without this a refusal is final -- including a temporary one, such
     * as a provider's daily limit, which is the likely kind. This moves the campaign's
     * FAILED rows back to PENDING and queues the campaign the same way {@link #sendNow}
     * does; the job then claims it and works through the queue. It is an admin's action
     * rather than automatic because nothing here can tell a limit that resets tomorrow from
     * an address that will never work, and guessing wrong in the automatic direction means
     * mailing a dead address over and over.
     *
     * <p>The list is not rebuilt: {@link CampaignSender#buildRecipients} keeps a campaign's
     * existing list, so nobody who opted in since is added. Rows still PENDING from a send
     * that broke part-way are picked up too, which makes this the way to resume a FAILED
     * campaign as well.
     *
     * <p>A FAILED row is not proof that nothing arrived -- a connection can drop after the
     * relay has accepted a message -- so a retry can, rarely, give somebody a second copy.
     */
    @Transactional
    public CampaignEntity retryFailed(String publicId) {
        requireEmailEnabled();
        CampaignEntity c = require(publicId);
        if (c.getStatus() != CampaignStatus.SENT && c.getStatus() != CampaignStatus.FAILED) {
            throw new ApiExceptions.BadRequestException(
                    "Only a campaign that has finished sending can be retried.");
        }
        if (c.getOfferValidUntil() != null && c.getOfferValidUntil().isBefore(today())) {
            throw new ApiExceptions.BadRequestException(
                    "This offer ended on " + DAY.format(c.getOfferValidUntil())
                            + ", so a retry would send a code that no longer works.");
        }
        int requeued = recipients.requeueFailed(c.getId());
        // A send that broke before its list was written has nobody PENDING yet, but does
        // have people to reach: the claim will list them.
        boolean neverListed = c.getStatus() == CampaignStatus.FAILED
                && recipients.countByCampaignId(c.getId()) == 0;
        if (!neverListed && recipients.countByCampaignIdAndStatus(c.getId(), "PENDING") == 0) {
            throw new ApiExceptions.BadRequestException(
                    "Everybody on this campaign's list was sent to or opted out;"
                            + " there is nobody to retry.");
        }
        c.setScheduledAt(clock.instant());
        c.setStatus(CampaignStatus.SCHEDULED);
        c.touch();
        log.info("Campaign {} queued for retry; {} failed recipient(s) requeued",
                c.getPublicId(), requeued);
        return c;
    }

    /**
     * Claim a campaign, materialise its audience, and work through it.
     *
     * <p>Called by {@link CampaignScheduleJob} only, never on a request thread -- see
     * {@link #sendNow} for why.
     *
     * <p>Deliberately not {@code @Transactional}. A send takes as long as it takes, and
     * holding one transaction across hundreds of SMTP round trips would pin a connection
     * for the duration and roll back every recorded success if the last message failed.
     *
     * @return how many messages the mail server accepted
     */
    public int dispatch(Long campaignId) {
        if (!sender.claim(campaignId)) {
            // Somebody else owns this send. Not an error — the guard working.
            log.debug("Campaign {} is already being sent elsewhere", campaignId);
            return 0;
        }
        CampaignEntity campaign = campaigns.findById(campaignId).orElseThrow();
        try {
            int listed = sender.buildRecipients(campaignId, resolve(campaign.getAudience()));
            log.info("Campaign {} claimed; {} recipients listed", campaign.getPublicId(), listed);

            int sent = 0;
            while (true) {
                List<CampaignRecipientEntity> batch = recipients.findByCampaignIdAndStatus(
                        campaignId, "PENDING", Limit.of(BATCH));
                if (batch.isEmpty()) {
                    break;
                }
                for (CampaignRecipientEntity r : batch) {
                    if (sender.sendOne(campaignId, r.getId())) {
                        sent++;
                    }
                }
            }
            sender.complete(campaignId, CampaignStatus.SENT);
            log.info("Campaign {} finished: {} sent", campaign.getPublicId(), sent);
            return sent;
        } catch (RuntimeException e) {
            log.error("Campaign {} failed mid-send", campaign.getPublicId(), e);
            sender.complete(campaignId, CampaignStatus.FAILED);
            throw e;
        }
    }

    // ---- consent and tracking ----------------------------------------------

    /**
     * Withdraw promotional consent.
     *
     * <p>Touches {@code marketingOptIn} and nothing else. The customer keeps receiving
     * order email, because that is not what they asked to stop.
     *
     * @return true if the account was found — including when it was already unsubscribed,
     *         which is a success from the customer's point of view and must not read as
     *         an error
     */
    @Transactional
    public boolean unsubscribe(UUID accountToken, String campaignPublicId) {
        Optional<AccountEntity> found = accounts.findByMarketingToken(accountToken);
        if (found.isEmpty()) {
            return false;
        }
        AccountEntity account = found.get();
        if (!account.isMarketingOptIn()) {
            return true;
        }
        Long campaignId = campaignPublicId == null ? null
                : campaigns.findByPublicId(campaignPublicId)
                        .map(CampaignEntity::getId).orElse(null);
        account.optOutOfMarketing(campaignId);
        accounts.save(account);
        log.info("Account {} unsubscribed from marketing (campaign {})",
                account.getPublicId(), campaignPublicId);
        return true;
    }

    @Transactional
    public void recordOpen(UUID recipientToken) {
        recipients.findByToken(recipientToken).ifPresent(r -> {
            r.markOpened();
            recipients.save(r);
        });
    }

    /** @return where to send the reader, or empty if the token is unknown */
    @Transactional
    public Optional<String> recordClick(UUID recipientToken) {
        return recipients.findByToken(recipientToken).flatMap(r -> {
            r.markClicked();
            recipients.save(r);
            return campaigns.findById(r.getCampaignId()).map(renderer::ctaUrlFor);
        });
    }

    @Transactional(readOnly = true)
    public Optional<CampaignEntity> withBanner(String publicId) {
        return campaigns.findByPublicId(publicId).filter(CampaignEntity::hasBanner);
    }

    // ---- internals ---------------------------------------------------------

    private CampaignEntity require(String publicId) {
        return campaigns.findByPublicId(publicId).orElseThrow(
                () -> new ApiExceptions.NotFoundException("No such campaign."));
    }

    private CampaignEntity requireEditable(String publicId) {
        CampaignEntity c = require(publicId);
        if (!c.getStatus().editable()) {
            throw new ApiExceptions.BadRequestException(
                    "This campaign is " + c.getStatus().name().toLowerCase()
                            + " and can no longer be edited.");
        }
        return c;
    }

    /**
     * Refuse to start anything that would put a campaign in the post while email is off.
     *
     * <p>{@code GFS_EMAIL_ENABLED} is the switch for everything this server sends,
     * campaigns and test copies included, not only order mail. Before this check a
     * campaign sent with it off went to whatever relay was configured anyway, and every
     * recipient the relay refused was recorded as FAILED. Reproduced locally: with the
     * flag off, both recipients went to the relay and both were marked FAILED.
     */
    private void requireEmailEnabled() {
        if (!props.notifications().emailEnabled()) {
            throw new ApiExceptions.BadRequestException(
                    "Email is switched off on this server, so nothing can be sent or scheduled."
                            + " Set GFS_EMAIL_ENABLED=true to send campaigns.");
        }
    }

    /** What must hold before anything reaches a customer. */
    private void requireSendable(CampaignEntity c) {
        if (isBlank(c.getSubject()) || isBlank(c.getHeading()) || isBlank(c.getBody())) {
            throw new ApiExceptions.BadRequestException(
                    "A campaign needs a subject, a heading and a message before it can go out.");
        }
        if (c.getOfferValidUntil() != null && c.getOfferValidUntil().isBefore(today())) {
            throw new ApiExceptions.BadRequestException(
                    "This offer ended on " + DAY.format(c.getOfferValidUntil())
                            + ". Change the date or clear it before sending.");
        }
        if (audienceSize(c.getAudience()) == 0) {
            // Not silently succeeding on an empty list: an admin who schedules a campaign
            // to nobody should be told now, not discover it in the analytics afterwards.
            throw new ApiExceptions.BadRequestException(
                    "No opted-in customers match this audience, so there is nobody to send to.");
        }
    }

    /** Today, in the business's calendar. */
    LocalDate today() {
        return clock.instant().atZone(BUSINESS_ZONE).toLocalDate();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
