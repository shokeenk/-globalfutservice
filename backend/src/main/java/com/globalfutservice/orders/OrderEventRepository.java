package com.globalfutservice.orders;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface OrderEventRepository extends JpaRepository<OrderEventEntity, Long> {
    List<OrderEventEntity> findByOrderIdOrderByCreatedAtAsc(Long orderId);

    /**
     * How many orders were in one of these statuses at a moment in the past.
     *
     * <p>Rebuilt from the history rather than stored: every status change writes an
     * event, and an order's status at any instant is the {@code to_status} of its last
     * event at or before it. An order created after that instant has no such event and is
     * not counted, which is right: it did not exist yet.
     */
    @Query(value = """
            select count(*) from (
                select distinct on (e.order_id) e.to_status
                  from order_event e
                 where e.created_at <= :at
                 order by e.order_id, e.created_at desc, e.id desc
            ) latest
            where latest.to_status in (:statuses)
            """, nativeQuery = true)
    long countInStatusesAt(@Param("at") Instant at, @Param("statuses") Collection<String> statuses);

    /**
     * The orders whose status changed most recently, newest first: the dashboard's Recent
     * Orders. Rows of {@code [order_id, last_change]}.
     */
    @Query(value = """
            select e.order_id, max(e.created_at) as last_change
              from order_event e
             group by e.order_id
             order by last_change desc
             limit :limit
            """, nativeQuery = true)
    List<Object[]> latestActivity(@Param("limit") int limit);
}
