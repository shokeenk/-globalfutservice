package com.globalfutservice.marketing;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.notify.email.TransactionalEmails;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The transactional steps of a send, in their own bean.
 *
 * <p><b>Why this is not just more methods on CampaignService.</b> Spring's
 * {@code @Transactional} is implemented with a proxy, and a proxy is only involved when a
 * call arrives from outside the object. A service calling its own {@code @Transactional}
 * method goes straight to the implementation and the annotation does nothing at all —
 * silently, with no warning and no failure until something needs to roll back and
 * doesn't. Putting each committed step behind a real bean boundary is what makes those
 * annotations mean anything.
 *
 * <p>Each recipient is its own transaction, deliberately. One bad address must not roll
 * back the two hundred messages that already went out, and a send that dies mid-campaign
 * must leave every row it completed marked SENT so the resume does not repeat them.
 */
@Component
public class CampaignSender {

    private static final Logger log = LoggerFactory.getLogger(CampaignSender.class);

    private final CampaignRepository campaigns;
    private final CampaignRecipientRepository recipients;
    private final AccountRepository accounts;
    private final JavaMailSender mailSender;
    private final AppProperties props;
    private final CampaignRenderer renderer;

    public CampaignSender(CampaignRepository campaigns,
                          CampaignRecipientRepository recipients,
                          AccountRepository accounts,
                          JavaMailSender mailSender,
                          AppProperties props,
                          CampaignRenderer renderer) {
        this.campaigns = campaigns;
        this.recipients = recipients;
        this.accounts = accounts;
        this.mailSender = mailSender;
        this.props = props;
        this.renderer = renderer;
    }

    /**
     * Take ownership of a campaign, in its own short transaction.
     *
     * <p>Separate from the send for two reasons that pull in opposite directions. The
     * claim is a modifying statement and must be transactional — without one, Hibernate
     * refuses to flush it. The send must NOT be, because it runs for as long as the
     * recipient list takes and would otherwise hold a transaction open across hundreds of
     * SMTP round trips.
     *
     * @return true if this caller now owns the send
     */
    @Transactional
    public boolean claim(Long campaignId) {
        return campaigns.claimForSending(campaignId, Instant.now()) == 1;
    }

    /**
     * Write one row per person, once.
     *
     * <p>Idempotent by construction: the unique constraint on (campaign, account) means a
     * re-run after a crash adds only the people not already listed. Rows are inserted one
     * at a time on the retry path for exactly that reason — a bulk insert fails whole.
     */
    @Transactional
    public int buildRecipients(Long campaignId, List<AccountEntity> people) {
        List<CampaignRecipientEntity> fresh = new ArrayList<>(people.size());
        for (AccountEntity a : people) {
            fresh.add(new CampaignRecipientEntity(campaignId, a.getId(), a.getEmail()));
        }
        try {
            recipients.saveAll(fresh);
        } catch (RuntimeException e) {
            log.warn("Bulk recipient insert clashed for campaign {}; inserting individually",
                    campaignId);
            for (CampaignRecipientEntity row : fresh) {
                try {
                    recipients.save(row);
                } catch (RuntimeException ignored) {
                    // Already listed from a previous run. That is the constraint doing
                    // its job, not a failure.
                }
            }
        }
        return (int) recipients.countByCampaignId(campaignId);
    }

    /**
     * One message, and the row recording what happened to it.
     *
     * <p>Consent is re-checked here rather than trusted from the list. A long send gives
     * somebody time to unsubscribe from the copy they already have, and continuing to mail
     * them because a list built ten minutes ago said they were opted in is precisely the
     * failure the unsubscribe link exists to prevent.
     */
    @Transactional
    public boolean sendOne(Long campaignId, Long recipientId) {
        CampaignRecipientEntity row = recipients.findById(recipientId).orElse(null);
        if (row == null || !"PENDING".equals(row.getStatus())) {
            return false;
        }
        CampaignEntity campaign = campaigns.findById(campaignId).orElse(null);
        if (campaign == null) {
            return false;
        }
        Optional<AccountEntity> account = accounts.findById(row.getAccountId());
        if (account.isEmpty() || !account.get().isMarketingOptIn()) {
            row.markSkipped("Opted out before this message was sent");
            recipients.save(row);
            return false;
        }
        try {
            String unsubscribe = renderer.unsubscribeUrl(
                    account.get().getMarketingToken(), campaign.getPublicId());
            TransactionalEmails.Rendered rendered = renderer.forRecipient(
                    campaign, unsubscribe, row.getToken());

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                    StandardCharsets.UTF_8.name());
            helper.setFrom(props.notifications().emailFrom(),
                    props.notifications().emailFromName());
            helper.setTo(row.getEmail());
            helper.setSubject(campaign.getSubject());
            helper.setText(rendered.text(), rendered.html());
            /*
             * One-click unsubscribe, RFC 8058.
             *
             * Gmail and Outlook render this as a native Unsubscribe control beside the
             * sender name. Offering it is the difference between somebody using it and
             * somebody reporting the message as spam — and spam reports damage the
             * sending domain for every campaign after this one.
             */
            message.addHeader("List-Unsubscribe", "<" + unsubscribe + ">");
            message.addHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");

            mailSender.send(message);
            row.markSent();
            recipients.save(row);
            return true;
        } catch (Exception e) {
            row.markFailed(e.getMessage());
            recipients.save(row);
            log.warn("Campaign {} to {} failed: {}",
                    campaign.getPublicId(), row.getEmail(), e.getMessage());
            return false;
        }
    }

    @Transactional
    public void complete(Long campaignId, CampaignStatus status) {
        campaigns.findById(campaignId).ifPresent(c -> {
            c.setStatus(status);
            c.setCompletedAt(Instant.now());
            c.touch();
        });
    }
}
