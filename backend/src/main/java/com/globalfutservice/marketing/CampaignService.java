package com.globalfutservice.marketing;

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

import java.time.Instant;
import java.util.List;
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

    private final CampaignRepository campaigns;
    private final CampaignRecipientRepository recipients;
    private final MarketingAudienceRepository audience;
    private final AccountRepository accounts;
    private final CampaignSender sender;
    private final CampaignRenderer renderer;

    public CampaignService(CampaignRepository campaigns,
                           CampaignRecipientRepository recipients,
                           MarketingAudienceRepository audience,
                           AccountRepository accounts,
                           CampaignSender sender,
                           CampaignRenderer renderer) {
        this.campaigns = campaigns;
        this.recipients = recipients;
        this.audience = audience;
        this.accounts = accounts;
        this.sender = sender;
        this.renderer = renderer;
    }

    // ---- composing ---------------------------------------------------------

    @Transactional
    public CampaignEntity create(String title, String subject, String heading, String body,
                                 CampaignAudience segment, Long adminId) {
        return campaigns.save(new CampaignEntity(title, subject, heading, body, segment, adminId));
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

    // ---- sending -----------------------------------------------------------

    @Transactional
    public CampaignEntity schedule(String publicId, Instant when) {
        CampaignEntity c = requireEditable(publicId);
        if (when == null || !when.isAfter(Instant.now())) {
            throw new ApiExceptions.BadRequestException("Pick a time in the future.");
        }
        requireSendable(c);
        c.setScheduledAt(when);
        c.setStatus(CampaignStatus.SCHEDULED);
        c.touch();
        log.info("Campaign {} scheduled for {}", c.getPublicId(), when);
        return c;
    }

    /** Validate and hand to {@link #dispatch}. Not transactional: the send is not one. */
    public int sendNow(String publicId) {
        CampaignEntity c = readForSend(publicId);
        return dispatch(c.getId());
    }

    @Transactional(readOnly = true)
    protected CampaignEntity readForSend(String publicId) {
        CampaignEntity c = require(publicId);
        if (c.getStatus() != CampaignStatus.DRAFT && c.getStatus() != CampaignStatus.SCHEDULED) {
            throw new ApiExceptions.BadRequestException(
                    "Only a draft or scheduled campaign can be sent.");
        }
        requireSendable(c);
        return c;
    }

    /**
     * Claim a campaign, materialise its audience, and work through it.
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

    /** What must hold before anything reaches a customer. */
    private void requireSendable(CampaignEntity c) {
        if (isBlank(c.getSubject()) || isBlank(c.getHeading()) || isBlank(c.getBody())) {
            throw new ApiExceptions.BadRequestException(
                    "A campaign needs a subject, a heading and a message before it can go out.");
        }
        if (audienceSize(c.getAudience()) == 0) {
            // Not silently succeeding on an empty list: an admin who schedules a campaign
            // to nobody should be told now, not discover it in the analytics afterwards.
            throw new ApiExceptions.BadRequestException(
                    "No opted-in customers match this audience, so there is nobody to send to.");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
