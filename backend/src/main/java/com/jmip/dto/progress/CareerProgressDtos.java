package com.jmip.dto.progress;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * V9.13: the signed-in user's career progress, computed on the server from data JMIP already keeps.
 * No ids are exposed; nothing here is stored.
 */
public final class CareerProgressDtos {

    private CareerProgressDtos() {
    }

    /**
     * @param score      0 to 100, the sum of the components' points
     * @param level      a plain-language reading of the score
     * @param components every part of the score, so the total is never a black box
     */
    public record Readiness(int score, String level, List<Component> components) {
    }

    /** @param hint what would add points next; absent when the component is full */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Component(String key, String label, int points, int maxPoints, String detail, String hint) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TargetRole(String role, String category, Double percentComplete, int skillsTotal, int skillsOnResume,
                             int skillsCompleted, int skillsInProgress, List<String> nextSkills) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LearningProgress(int items, int completed, int inProgress, Double averageProgress) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InterviewProgress(int completed, Double averageScore, Double latestScore) {
    }

    /** Current statuses of the saved jobs: applied counts every job that got past SAVED. */
    public record ApplicationProgress(int saved, int applied, int interviewing, int offers) {
    }

    /** @param target absent without an active career goal */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Progress(TargetRole target, LearningProgress learning, InterviewProgress interviews,
                           ApplicationProgress applications) {
    }

    /**
     * A one-time milestone. Repeating an action never earns it again.
     *
     * @param achievedOn when it happened, if the data records that
     * @param progress   how far along an unearned one is, e.g. "2 of 5 completed"
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Achievement(String key, String title, String description, String category, boolean achieved,
                              LocalDate achievedOn, String progress) {
    }

    /**
     * Consecutive weeks (Monday to Sunday) with at least one real step; weeks rather than days, so a
     * streak never asks for activity just to keep it alive. The current week counts once it has
     * activity, and does not break the streak before it ends.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Streak(String key, String label, int currentWeeks, int longestWeeks, boolean activeThisWeek,
                         LocalDate lastActiveOn) {
    }

    /**
     * @param nextMilestones the next few achievements to aim for, with how to earn them
     * @param notes          what the score does and does not count
     */
    public record CareerProgress(Readiness readiness, Progress progress, List<Achievement> achievements,
                                 List<Achievement> nextMilestones, List<Streak> streaks, List<String> notes) {
    }
}
