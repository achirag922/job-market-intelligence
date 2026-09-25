package com.jmip.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;

/** V7.4: the user's progress on one skill of one goal's roadmap. */
@Entity
@Table(name = "career_goal_skill_progress")
@IdClass(CareerGoalSkillProgress.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CareerGoalSkillProgress {

    @Id
    @Column(name = "goal_id")
    private UUID goalId;

    @Id
    @Column(name = "skill_id")
    private Long skillId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SkillProgressStatus status;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public CareerGoalSkillProgress(UUID goalId, Long skillId, SkillProgressStatus status, OffsetDateTime now) {
        this.goalId = goalId;
        this.skillId = skillId;
        change(status, now);
    }

    public void change(SkillProgressStatus status, OffsetDateTime now) {
        this.status = status;
        this.updatedAt = now;
    }

    public record Key(UUID goalId, Long skillId) implements Serializable {
    }
}
