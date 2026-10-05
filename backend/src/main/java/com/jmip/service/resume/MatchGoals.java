package com.jmip.service.resume;

import com.jmip.entity.CareerGoal;
import com.jmip.entity.CareerGoalStatus;
import com.jmip.entity.Skill;
import com.jmip.repository.CareerGoalRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** V9.3: the owner's most recently updated active career goal, as the matching engine needs it. */
@Component
public class MatchGoals {

    private final CareerGoalRepository goals;

    public MatchGoals(CareerGoalRepository goals) {
        this.goals = goals;
    }

    @Transactional(readOnly = true)
    public JobMatchScorer.GoalContext forUser(UUID userId) {
        if (userId == null) {
            return JobMatchScorer.GoalContext.NONE;
        }
        return goals.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, CareerGoalStatus.ACTIVE).stream().findFirst()
                .map(MatchGoals::context).orElse(JobMatchScorer.GoalContext.NONE);
    }

    private static JobMatchScorer.GoalContext context(CareerGoal goal) {
        Set<Long> skills = goal.getTargetSkills().stream().map(Skill::getId).collect(Collectors.toSet());
        return new JobMatchScorer.GoalContext(goal.getTargetRole(), goal.getTargetCategory(), skills);
    }
}
