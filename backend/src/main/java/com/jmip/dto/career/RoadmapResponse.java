package com.jmip.dto.career;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jmip.dto.SkillResponse;
import com.jmip.entity.SkillProgressStatus;

import java.util.List;
import java.util.UUID;

/**
 * V7.4: a goal's skill roadmap. Every figure comes from existing data: market numbers from
 * the V4 category analytics, trends from the V6.1 trend analytics, current skills from the
 * user's resume. Skills are ranked, not staged: JMIP holds no data on how advanced a skill is.
 *
 * @param basedOnResume the resume whose skills count as current; absent when there is none
 * @param marketSkills  the skills most asked for in the goal's category, with their demand
 * @param coveredSkills roadmap skills already on the resume
 * @param roadmap       skills still to develop, in priority order
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoadmapResponse(
        UUID goalId,
        String targetRole,
        String targetCategory,
        ResumeRef basedOnResume,
        List<SkillResponse> currentSkills,
        List<MarketSkill> marketSkills,
        List<SkillResponse> coveredSkills,
        List<RoadmapSkill> roadmap,
        Progress progress,
        boolean staged,
        String note) {

    public record ResumeRef(UUID id, String title) {
    }

    /** A skill's demand in the category, exactly as the category analytics report it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MarketSkill(Long skillId, String skill, String category, long jobCount, double percentageOfJobs,
                              int demandRank, Double trendChangeInPercentagePoints, boolean onResume) {
    }

    /**
     * @param source   MARKET_DEMAND (from the category's postings) or YOUR_CHOICE (added to the goal)
     * @param reason   why it sits where it does, in the figures above
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RoadmapSkill(int priority, Long skillId, String skill, String category, Source source,
                               Double percentageOfJobs, Integer demandRank, Double trendChangeInPercentagePoints,
                               String reason, SkillProgressStatus status) {
    }

    public enum Source {
        MARKET_DEMAND,
        YOUR_CHOICE
    }

    /**
     * @param percentComplete skills on the resume or marked completed, out of all roadmap skills;
     *                        absent when the roadmap has no skills
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Progress(int totalSkills, int onResume, int completed, int inProgress, int notStarted,
                           Double percentComplete) {
    }
}
