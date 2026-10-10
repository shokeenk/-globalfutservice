package com.globalfutservice.pricing;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponDeletionRepository extends JpaRepository<CouponDeletionEntity, Long> {

    Page<CouponDeletionEntity> findAllByOrderByDeletedAtDesc(Pageable pageable);
}
