package com.globalfutservice.pricing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CouponRedemptionRepository extends JpaRepository<CouponRedemptionEntity, Long> {

    /** Backs the per-account limit. */
    long countByCouponIdAndAccountId(Long couponId, Long accountId);

    Optional<CouponRedemptionEntity> findByOrderId(Long orderId);

    boolean existsByOrderId(Long orderId);
}
