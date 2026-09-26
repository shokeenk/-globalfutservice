package com.globalfutservice.marketing;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
     * Put a campaign's failed rows back in the send queue.
     *
     * <p>SKIPPED rows -- people who opted out before their message went -- are left alone:
     * that is a decision the customer made, not a delivery that went wrong.
     *
     * @return how many rows were requeued
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update CampaignRecipientEntity r
               set r.status = 'PENDING', r.failedAt = null, r.error = null
             where r.campaignId = :campaignId
               and r.status = 'FAILED'
            """)
    int requeueFailed(@Param("campaignId") Long campaignId);

    /** Campaign messages the provider accepted since a moment, across every campaign. */
    long countByStatusAndSentAtAfter(String status, java.time.Instant since);

    /**
     * The analytics row for one campaign, in a single pass.
     *
     * <p>Counted rather than stored on the campaign, because a stored counter and a set of
     * rows eventually disagree and then nobody knows which to believe.
     *
     * <p>Opens and clicks are counts of <i>distinct recipients observed</i>, not events:
     * the entity records only the first of each, so a mail client that pre-fetches an
     * image five times still counts once.
     *
     * <p><b>Every sum is wrapped in {@code coalesce}, and must stay that way.</b> An
     * aggregate query with no {@code group by} always returns exactly one row, even when
     * nothing matches — and in that row {@code count} is 0 while every {@code sum} is
     * NULL, because the sum of no values is not zero in SQL, it is unknown. The record
     * this projects into takes primitive {@code long}s, so a NULL arriving there fails
     * inside Hibernate's instantiation with an unboxing error rather than anything that
     * names the column.
     *
     * <p>That is not an edge case: it is every campaign that has no recipient rows, which
     * means every draft that has never been sent. It made creating a campaign return 500
     * after the row had already been committed, so the draft existed and the admin was
     * told the server had broken.
     */
    @Query("""
            select new com.globalfutservice.marketing.CampaignStats(
                       count(r),
                       coalesce(sum(case when r.status = 'SENT'   then 1L else 0L end), 0L),
                       coalesce(sum(case when r.status = 'FAILED' then 1L else 0L end), 0L),
                       coalesce(sum(case when r.openedAt  is not null then 1L else 0L end), 0L),
                       coalesce(sum(case when r.clickedAt is not null then 1L else 0L end), 0L))
              from CampaignRecipientEntity r
             where r.campaignId = :campaignId
            """)
    CampaignStats statsFor(@Param("campaignId") Long campaignId);
}
