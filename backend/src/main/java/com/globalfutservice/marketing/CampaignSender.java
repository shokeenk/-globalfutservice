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
import java.time.LocalDate;
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
     * Take over a send that stopped without finishing.
     *
     * @return true if this caller now owns the send
     */
    @Transactional
    public boolean reclaimStalled(Long campaignId, Instant now, Instant staleBefore) {
        return campaigns.reclaimStalled(campaignId, now, staleBefore) == 1;
    }

    /**
     * Say this send is still alive. Its own short transaction, before each message, so
     * the campaign row is never held locked across an SMTP round trip.
     */
    @Transactional
    public void heartbeat(Long campaignId) {
        campaigns.heartbeat(campaignId, Instant.now());
    }

    /**
     * Withdraw a due campaign whose offer ended before it could go out.
     *
     * @param today the business's today, in which an offer's last day is counted
     * @return true if the campaign was withdrawn and must not be sent
     */
    @Transactional
    public boolean withdrawIfOfferEnded(Long campaignId, LocalDate today) {
        return campaigns.withdrawIfOfferEnded(campaignId, today, Instant.now()) == 1;
    }

    /**
     * Write one row per person, the first time a campaign is claimed, and never again.
     *
     * <p>The list is fixed when the send starts. A campaign that is claimed again -- to
     * retry the people it failed to reach -- keeps the list it already has and adds
     * nobody, so a customer who opted in since does not receive a promotion that went out
     * days before they joined. It also means the (campaign, account) unique constraint is
     * a backstop that should never fire, not a mechanism this method leans on.
     *
     * <p>Why it does not try to "top up" an existing list instead: that was the original
     * design, and it could not work. The rows are inserted immediately (the id is an
     * identity column), so the first duplicate throws inside the insert. Postgres then
     * refuses every further statement in the transaction and Hibernate refuses to flush the
     * session, so neither inserting the rest one by one nor counting them afterwards can
     * succeed. Reproduced against Postgres: the count failed with Hibernate's "don't flush
     * the Session after an exception occurs" and the one person not already listed was
     * never added.
     *
     * <p>A list is written whole or not at all, in this one transaction, so "has rows"
     * reliably means "was completely listed". If the insert does clash, the exception
     * propagates and the caller marks the send FAILED -- loudly, rather than swallowing it
     * and carrying on with a list nobody has checked.
     *
     * @return how many people the campaign is going to
     */
    @Transactional
    public int buildRecipients(Long campaignId, List<AccountEntity> people) {
        long existing = recipients.countByCampaignId(campaignId);
        if (existing > 0) {
            return (int) existing;
        }
        List<CampaignRecipientEntity> fresh = new ArrayList<>(people.size());
        for (AccountEntity a : people) {
            fresh.add(new CampaignRecipientEntity(campaignId, a.getId(), a.getEmail()));
        }
        recipients.saveAll(fresh);
        return fresh.size();
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

    /**
     * One copy of a campaign to the admin who asked for it, and nobody else.
     *
     * <p>Not a recipient: no row is written, nothing is counted, and the copy is the
     * inert preview -- no pixel, no click counter, a dummy unsubscribe link -- so testing
     * a campaign cannot skew its numbers or unsubscribe the person testing it. The
     * address is the signed-in account's own, looked up on the server; there is no way
     * to name another, which is what keeps this from being a way round consent.
     *
     * @throws IllegalStateException carrying the mail server's refusal, so the admin sees
     *         why a test did not arrive rather than a generic failure
     */
    public void sendTest(String to, TransactionalEmails.Rendered copy) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                    StandardCharsets.UTF_8.name());
            helper.setFrom(props.notifications().emailFrom(),
                    props.notifications().emailFromName());
            helper.setTo(to);
            helper.setSubject("[TEST] " + copy.subject());
            helper.setText(copy.text(), copy.html());
            mailSender.send(message);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
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
