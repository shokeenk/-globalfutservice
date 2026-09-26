package com.globalfutservice.marketing;

import com.globalfutservice.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Sends campaigns whose scheduled time has arrived.
 *
 * <p>Clock-driven on a one-minute tick. A minute is the right granularity here: campaigns
 * are scheduled to the minute by a human choosing "Friday 6pm", and nobody notices
 * thirty seconds. A tighter interval would mean polling the table sixty times as often for
 * a table that is usually empty.
 *
 * <p><b>Safe on more than one instance,</b> unlike the other scheduled jobs in this
 * codebase, which document that they assume a single node. The difference matters: a purge
 * sweep running twice does the same work twice, whereas a campaign running twice sends
 * every customer the promotion twice, and that is not something an apology fixes.
 * {@link CampaignRepository#claimForSending} is the guard — every instance may see the
 * same due campaign, exactly one wins the conditional update.
 *
 * <p>Also picks up sends that stopped part-way -- SENDING, with no sign of life for
 * {@link CampaignService#STALLED_AFTER} -- and finishes them. See
 * {@link CampaignService#resumeStalled}.
 *
 * <p>Holds everything back while {@code GFS_EMAIL_ENABLED} is off. A due campaign is left
 * SCHEDULED rather than claimed, so it goes out once email is switched on, instead of
 * being claimed and recording every recipient as FAILED against a relay nobody meant to
 * use.
 */
@Component
public class CampaignScheduleJob {

    private static final Logger log = LoggerFactory.getLogger(CampaignScheduleJob.class);

    private final CampaignRepository campaigns;
    private final CampaignService service;
    private final AppProperties props;

    public CampaignScheduleJob(CampaignRepository campaigns, CampaignService service,
                               AppProperties props) {
        this.campaigns = campaigns;
        this.service = service;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT30S")
    public void sendDueCampaigns() {
        Instant now = Instant.now();
        List<CampaignEntity> stalled = campaigns.findStalled(now.minus(CampaignService.STALLED_AFTER));
        List<CampaignEntity> due = campaigns.findDue(now);
        if (stalled.isEmpty() && due.isEmpty()) {
            return;
        }
        if (!props.notifications().emailEnabled()) {
            log.warn("{} campaign(s) due and {} stopped mid-send, but GFS_EMAIL_ENABLED is false;"
                    + " leaving them", due.size(), stalled.size());
            return;
        }
        for (CampaignEntity c : stalled) {
            try {
                service.resumeStalled(c.getId());
            } catch (RuntimeException e) {
                log.error("Campaign {} could not be resumed", c.getPublicId(), e);
            }
        }
        if (due.isEmpty()) {
            return;
        }
        log.info("{} campaign(s) due", due.size());
        for (CampaignEntity c : due) {
            try {
                service.dispatch(c.getId());
            } catch (RuntimeException e) {
                // One bad campaign must not stop the others. dispatch() has already
                // marked this one FAILED and logged the cause.
                log.error("Campaign {} could not be sent", c.getPublicId(), e);
            }
        }
    }
}
