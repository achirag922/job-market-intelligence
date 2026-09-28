package com.jmip.repository;

import com.jmip.entity.JobAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobAlertRepository extends JpaRepository<JobAlert, UUID> {

    List<JobAlert> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Scoped to the owner, so another account's alert is indistinguishable from a missing one. */
    Optional<JobAlert> findByIdAndUserId(UUID id, UUID userId);

    long countByUserId(UUID userId);
}
