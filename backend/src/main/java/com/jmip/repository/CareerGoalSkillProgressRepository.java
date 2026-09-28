package com.jmip.repository;

import com.jmip.entity.CareerGoalSkillProgress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Reached only through a goal the caller already owns. */
public interface CareerGoalSkillProgressRepository
        extends JpaRepository<CareerGoalSkillProgress, CareerGoalSkillProgress.Key> {

    List<CareerGoalSkillProgress> findByGoalId(UUID goalId);
}
