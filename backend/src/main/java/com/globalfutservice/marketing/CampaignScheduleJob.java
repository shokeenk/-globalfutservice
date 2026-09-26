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
        List<CampaignEntity> due = campaigns.findDue(Instant.now());
        if (due.isEmpty()) {
            return;
        }
        if (!props.notifications().emailEnabled()) {
            log.warn("{} campaign(s) due, but GFS_EMAIL_ENABLED is false; leaving them scheduled",
                    due.size());
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
