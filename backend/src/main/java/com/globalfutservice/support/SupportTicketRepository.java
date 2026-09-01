package com.globalfutservice.support;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SupportTicketRepository extends JpaRepository<SupportTicketEntity, Long> {

    Optional<SupportTicketEntity> findByPublicRef(String publicRef);

    Page<SupportTicketEntity> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    Page<SupportTicketEntity> findByAccountIdOrderByCreatedAtDesc(Long accountId, Pageable pageable);
}
