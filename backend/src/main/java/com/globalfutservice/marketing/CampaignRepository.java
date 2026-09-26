package com.globalfutservice.marketing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CampaignRepository extends JpaRepository<CampaignEntity, Long> {

    Optional<CampaignEntity> findByPublicId(String publicId);

    List<CampaignEntity> findByStatusOrderByUpdatedAtDesc(CampaignStatus status);

    List<CampaignEntity> findByStatusInOrderByUpdatedAtDesc(List<CampaignStatus> statuses);

    /**
     * Scheduled campaigns whose time has come.
     *
     * <p>Ordered oldest-first so a backlog after downtime goes out in the order it was
     * meant to, rather than newest-first with yesterday's sale arriving after today's.
     */
    @Query("""
            select c from CampaignEntity c
            where c.status = com.globalfutservice.marketing.CampaignStatus.SCHEDULED
              and c.scheduledAt <= :now
            order by c.scheduledAt asc
            """)
    List<CampaignEntity> findDue(@Param("now") Instant now);

    /**
     * Take ownership of a campaign, atomically.
     *
     * <p>This is the whole concurrency story. Two application instances running the same
     * scheduler will both see the same due campaign; exactly one of them gets a row count
     * of 1 back from this statement and proceeds, and the other gets 0 and does nothing.
     * Without it the second instance sends the entire campaign a second time.
     *
     * <p>Kept as a conditional UPDATE rather than a SELECT ... FOR UPDATE so it holds no
     * lock for the duration of a send that may take minutes.
     *
     * @return 1 if this caller now owns the send, 0 if somebody else got there first
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update CampaignEntity c
               set c.status = com.globalfutservice.marketing.CampaignStatus.SENDING,
                   c.startedAt = :now,
                   c.updatedAt = :now
             where c.id = :id
               and c.status in (com.globalfutservice.marketing.CampaignStatus.SCHEDULED,
                                com.globalfutservice.marketing.CampaignStatus.DRAFT)
            """)
    int claimForSending(@Param("id") Long id, @Param("now") Instant now);

    /**
     * Withdraw a scheduled campaign whose offer has already ended, instead of sending it.
     *
     * <p>Conditional, like the claim, so it cannot race an admin who is changing the
     * campaign at the same moment: it only acts on a campaign that is still SCHEDULED.
     *
     * @return 1 if the campaign was withdrawn
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update CampaignEntity c
               set c.status = com.globalfutservice.marketing.CampaignStatus.CANCELLED,
                   c.updatedAt = :now
             where c.id = :id
               and c.status = com.globalfutservice.marketing.CampaignStatus.SCHEDULED
               and c.offerValidUntil < :today
            """)
    int withdrawIfOfferEnded(@Param("id") Long id, @Param("today") LocalDate today,
                             @Param("now") Instant now);
}
