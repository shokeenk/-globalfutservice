package com.globalfutservice.payments;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RefundRepository extends JpaRepository<RefundEntity, Long> {

    Optional<RefundEntity> findByOrderId(Long orderId);

    boolean existsByOrderId(Long orderId);
}
