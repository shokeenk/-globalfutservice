package com.globalfutservice.payments.payop;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PayopFeeMethodRepository extends JpaRepository<PayopFeeMethodEntity, Long> {

    List<PayopFeeMethodEntity> findByActiveTrue();

    List<PayopFeeMethodEntity> findAllByOrderByRegionAscNameAsc();
}
