package com.globalfutservice.notify.feed;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface CustomerNotificationRepository extends JpaRepository<CustomerNotificationEntity, Long> {

    List<CustomerNotificationEntity> findByAccountIdOrderByCreatedAtDesc(Long accountId, Pageable pageable);

    long countByAccountIdAndReadAtIsNull(Long accountId);

    /**
     * Marks everything this account has not read.
     *
     * <p>One statement rather than a read-modify-write per row: opening the bell is the
     * common case and a customer with forty unread rows should not cost forty updates.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CustomerNotificationEntity n set n.readAt = :at "
            + "where n.accountId = :accountId and n.readAt is null")
    int markAllRead(@Param("accountId") Long accountId, @Param("at") Instant at);
}
