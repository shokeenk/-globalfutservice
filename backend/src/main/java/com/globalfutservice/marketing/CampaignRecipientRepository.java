package com.globalfutservice.marketing;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CampaignRecipientRepository
        extends JpaRepository<CampaignRecipientEntity, Long> {

    Optional<CampaignRecipientEntity> findByToken(UUID token);

    /** The send loop's work queue, taken in batches so one campaign cannot hog memory. */
    List<CampaignRecipientEntity> findByCampaignIdAndStatus(Long campaignId, String status,
                                                            Limit limit);

    long countByCampaignId(Long campaignId);

    long countByCampaignIdAndStatus(Long campaignId, String status);

    /**
     * The analytics row for one campaign, in a single pass.
     *
     * <p>Counted rather than stored on the campaign, because a stored counter and a set of
     * rows eventually disagree and then nobody knows which to believe.
     *
     * <p>Opens and clicks are counts of <i>distinct recipients observed</i>, not events:
     * the entity records only the first of each, so a mail client that pre-fetches an
     * image five times still counts once.
     */
    @Query("""
            select new com.globalfutservice.marketing.CampaignStats(
                       count(r),
                       sum(case when r.status = 'SENT'   then 1L else 0L end),
                       sum(case when r.status = 'FAILED' then 1L else 0L end),
                       sum(case when r.openedAt  is not null then 1L else 0L end),
                       sum(case when r.clickedAt is not null then 1L else 0L end))
              from CampaignRecipientEntity r
             where r.campaignId = :campaignId
            """)
    CampaignStats statsFor(@Param("campaignId") Long campaignId);
}
