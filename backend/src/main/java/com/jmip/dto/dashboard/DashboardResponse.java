package com.jmip.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jmip.dto.SkillResponse;
import com.jmip.dto.analytics.SkillTrendResponse;
import com.jmip.dto.career.RoadmapResponse;
import com.jmip.dto.market.MarketResponses;
import com.jmip.entity.ApplicationStatus;
import com.jmip.entity.SkillProgressStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * V7.7: the signed-in user's career dashboard in one response. Every section is assembled
 * from an existing service (resumes, recommendations, roadmap, saved jobs, market
 * intelligence) and says whether it has data; {@code note} explains any section without.
 */
public record DashboardResponse(
        ResumeSection resume,
        SkillSection skills,
        RecommendationSection recommendations,
        ApplicationSection applications,
        GoalSection careerGoal,
        MarketSection market) {

    // ------------------------------------------------------------------ resume

    /**
     * @param current     the resume analyses use: the default if processed, else the newest processed
     * @param resumeCount all of the user's resumes, processed or not
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResumeSection(boolean available, ResumeSummary current, int resumeCount, MatchSummary matchSummary,
                                List<String> missingSkillsForGoal, String note) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResumeSummary(UUID id, String title, String versionLabel, boolean isDefault, int skillCount,
                                OffsetDateTime uploadedAt) {
    }

    /** Across the top recommendations (V6.3): how many there are, and their best and average match. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MatchSummary(int jobsCompared, Double topMatchPercentage, Double averageMatchPercentage) {
    }

    // ------------------------------------------------------------------ skills

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SkillSection(boolean available, List<SkillResponse> currentSkills, List<String> inProgress,
                               List<String> completed, int notStarted, Double roadmapPercentComplete, String note) {
    }

    // ------------------------------------------------------------------ recommendations

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecommendationSection(boolean available, int count, Double averageMatchPercentage,
                                        List<RecommendedJob> topJobs, String note) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecommendedJob(Long jobId, String title, String company, String category, double matchPercentage) {
    }

    // ------------------------------------------------------------------ applications

    /** @param funnel the open pipeline, SAVED to OFFER, by each job's current status */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApplicationSection(boolean available, int total, long saved, long applied, long interview, long offer,
                                     long rejected, long withdrawn, List<FunnelStage> funnel,
                                     List<RecentApplication> recent, String note) {
    }

    public record FunnelStage(ApplicationStatus stage, long jobs) {
    }

    /** No notes: they are the user's private free text and stay on the Saved Jobs page. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecentApplication(UUID savedJobId, Long jobId, String title, String company, ApplicationStatus status,
                                    OffsetDateTime appliedAt, OffsetDateTime updatedAt) {
    }

    // ------------------------------------------------------------------ career goal

    /** @param activeGoals how many ACTIVE goals the user has; the section shows the most recently changed */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GoalSection(boolean available, UUID goalId, String targetRole, String targetCategory, int activeGoals,
                              RoadmapResponse.Progress progress, List<PrioritySkill> topMissingSkills,
                              String basedOnResume, String note) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PrioritySkill(int priority, String skill, String reason, SkillProgressStatus status) {
    }

    // ------------------------------------------------------------------ market

    /**
     * The V7.5 market views for the goal's category, or for all postings without a goal.
     *
     * @param category   the category shown; absent means all postings
     * @param skillTrend the stored skill history, only offered for the all-postings view (V7.5)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MarketSection(boolean available, String category, MarketResponses.Scope scope,
                                List<MarketResponses.SkillFigure> topSkills, SkillTrendResponse skillTrend,
                                List<MarketResponses.LocationFigure> topLocations, long locationNotStated,
                                List<MarketResponses.ModeFigure> workModes, List<MarketResponses.SalaryFigure> salaries,
                                List<MarketResponses.CompanyFigure> topCompanies, List<String> notes) {
    }
}
