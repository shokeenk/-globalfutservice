package com.globalfutservice.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavedViewRepository extends JpaRepository<SavedViewEntity, Long> {

    List<SavedViewEntity> findByAccountIdAndPageOrderByNameAsc(Long accountId, String page);

    long countByAccountIdAndPage(Long accountId, String page);

    boolean existsByAccountIdAndPageAndNameIgnoreCase(Long accountId, String page, String name);

    /** Ownership is part of the lookup: another person's view is simply not found. */
    Optional<SavedViewEntity> findByIdAndAccountId(Long id, Long accountId);
}
