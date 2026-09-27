package com.globalfutservice.support;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupportMessageRepository extends JpaRepository<SupportMessageEntity, Long> {

    /** The whole thread, notes included: for staff only. */
    List<SupportMessageEntity> findByTicketIdOrderByCreatedAtAscIdAsc(Long ticketId);

    /** What the customer may read: messages, never notes. */
    List<SupportMessageEntity> findByTicketIdAndKindOrderByCreatedAtAscIdAsc(Long ticketId, String kind);
}
