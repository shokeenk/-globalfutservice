package com.globalfutservice.coaching;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CoachingSessionEventRepository
        extends JpaRepository<CoachingSessionEventEntity, Long> {

    List<CoachingSessionEventEntity> findBySessionIdOrderByCreatedAtAsc(Long sessionId);
}
