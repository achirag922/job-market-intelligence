package com.jmip.repository;

import com.jmip.entity.CareerGoal;
import com.jmip.entity.CareerGoalStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every finder is scoped to the owner: another account's goal is indistinguishable from a missing one. */
public interface CareerGoalRepository extends JpaRepository<CareerGoal, UUID> {

    @EntityGraph(attributePaths = "targetSkills")
    List<CareerGoal> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    @EntityGraph(attributePaths = "targetSkills")
    List<CareerGoal> findByUserIdAndStatusOrderByUpdatedAtDesc(UUID userId, CareerGoalStatus status);

    @EntityGraph(attributePaths = "targetSkills")
    Optional<CareerGoal> findByIdAndUserId(UUID id, UUID userId);

    long countByUserId(UUID userId);
}
