package com.globalfutservice.orders;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderEventRepository extends JpaRepository<OrderEventEntity, Long> {
    List<OrderEventEntity> findByOrderIdOrderByCreatedAtAsc(Long orderId);
}
