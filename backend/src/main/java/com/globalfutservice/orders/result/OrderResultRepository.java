package com.globalfutservice.orders.result;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OrderResultRepository extends JpaRepository<OrderResultEntity, Long> {

    /** Owed, nobody has chosen, and the time to choose is up: the default job's work. */
    default List<Long> findDue(Instant now) {
        return findAwaitingChoice(OrderResultEntity.Status.PENDING, now);
    }

    @Query("""
            select r.orderId from OrderResultEntity r
            where r.status = :status and r.method is null and r.chooseBy <= :now
            order by r.chooseBy
            """)
    List<Long> findAwaitingChoice(@Param("status") OrderResultEntity.Status status, @Param("now") Instant now);
}
