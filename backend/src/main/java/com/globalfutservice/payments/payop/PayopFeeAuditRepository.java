package com.globalfutservice.payments.payop;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayopFeeAuditRepository extends JpaRepository<PayopFeeAuditEntity, Long> {

    List<PayopFeeAuditEntity> findByMethodIdOrderByAtDescIdDesc(Long methodId);

    List<PayopFeeAuditEntity> findAllByOrderByAtDescIdDesc(Pageable page);
}
