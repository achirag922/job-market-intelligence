package com.jmip.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** V7.4: a role the user is working towards, and the skills they chose to develop for it. */
@Entity
@Table(name = "career_goals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CareerGoal {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "target_role", nullable = false)
    private String targetRole;

    /** A V4 job category; the roadmap's market demand is read from postings in it. */
    @Column(name = "target_category", nullable = false)
    private String targetCategory;

    @Column(name = "target_location")
    private String targetLocation;

    @Column(name = "target_experience")
    private String targetExperience;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CareerGoalStatus status;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "career_goal_skills",
            joinColumns = @JoinColumn(name = "goal_id"),
            inverseJoinColumns = @JoinColumn(name = "skill_id"))
    private Set<Skill> targetSkills = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public CareerGoal(UUID id, UUID userId, Details details, Collection<Skill> targetSkills, OffsetDateTime now) {
        this.id = id;
        this.userId = userId;
        this.status = CareerGoalStatus.ACTIVE;
        this.createdAt = now;
        update(details, targetSkills, now);
    }

    public void update(Details details, Collection<Skill> skills, OffsetDateTime now) {
        this.targetRole = details.targetRole();
        this.targetCategory = details.targetCategory();
        this.targetLocation = details.targetLocation();
        this.targetExperience = details.targetExperience();
        this.targetSkills.clear();
        this.targetSkills.addAll(skills);
        this.updatedAt = now;
    }

    public void changeStatus(CareerGoalStatus status, OffsetDateTime now) {
        this.status = status;
        this.updatedAt = now;
    }

    /** Validated, normalised goal fields. */
    public record Details(String targetRole, String targetCategory, String targetLocation, String targetExperience) {
    }
}
