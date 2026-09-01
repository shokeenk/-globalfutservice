package com.globalfutservice.coaching;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CoachAvailabilityRepository extends JpaRepository<CoachAvailabilityEntity, Long> {

    List<CoachAvailabilityEntity> findByCoachId(Long coachId);

    void deleteByCoachId(Long coachId);
}
