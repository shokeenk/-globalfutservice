package com.globalfutservice.popups;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PopupCampaignRepository extends JpaRepository<PopupCampaignEntity, Long> {

    Optional<PopupCampaignEntity> findByPublicIdAndDeletedAtIsNull(String publicId);

    List<PopupCampaignEntity> findAllByDeletedAtIsNullOrderByUpdatedAtDesc();

    /** Published and switched on; the schedule is checked by the caller against its clock. */
    @Query("select c from PopupCampaignEntity c where c.deletedAt is null and c.enabled = true "
            + "and c.status = com.globalfutservice.popups.PopupTypes.Status.PUBLISHED order by c.priority desc, c.id")
    List<PopupCampaignEntity> findSwitchedOn();
}
