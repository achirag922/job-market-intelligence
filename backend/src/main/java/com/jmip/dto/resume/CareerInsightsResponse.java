package com.jmip.dto.resume;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jmip.dto.SkillResponse;

import java.util.List;
import java.util.UUID;

/**
 * A resume set against the demand for one job category.
 *
 * <p>Every figure is copied from an existing analytics result — category skill demand
 * (V4), skill trends (V4) or the V3/V6.3 match score. Nothing here is estimated.
 *
 * @param resumeId         the resume the insights describe
 * @param targetCategory   the category compared against, null when none could be chosen
 * @param categorySource   whether the caller named the category or it was taken from the
 *                         resume's best recommendation
 * @param resumeSkills     every skill extracted from the resume
 * @param highDemandSkills the category's most requested skills, flagged if on the resume
 * @param strongSkills     resume skills the category's postings ask for
 * @param skillGaps        high-demand skills the resume does not show
 * @param trendingSkills   category skills whose market-wide share is rising
 * @param focusAreas       skill gaps ordered by rising demand, then by category demand
 * @param recommendedJobs  V6.3 recommendations that fall in the target category
 * @param summary          optional AI description of the figures above, null when not
 *                         requested or unavailable
 * @param note             why a section is empty or the summary is missing, null otherwise
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CareerInsightsResponse(
        UUID resumeId,
        String targetCategory,
        CategorySource categorySource,
        List<SkillResponse> resumeSkills,
        List<DemandSkill> highDemandSkills,
        List<DemandSkill> strongSkills,
        List<DemandSkill> skillGaps,
        List<TrendingSkill> trendingSkills,
        List<FocusArea> focusAreas,
        List<ResumeRecommendationResponse> recommendedJobs,
        String summary,
        String note) {

    public enum CategorySource {
        REQUESTED,
        TOP_RECOMMENDATION
    }

    /**
     * @param jobCount         RAW COUNT — category postings asking for the skill
     * @param percentageOfJobs PERCENTAGE — share of the category's postings
     * @param rank             DERIVED — position within the category, 1 being most requested
     * @param onResume         whether the resume shows the skill
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DemandSkill(
            Long skillId,
            String skill,
            String category,
            long jobCount,
            double percentageOfJobs,
            int rank,
            boolean onResume) {
    }

    /**
     * @param earlierSharePercentage   PERCENTAGE — market-wide share in the earlier half
     * @param recentSharePercentage    PERCENTAGE — market-wide share in the recent half
     * @param changeInPercentagePoints DERIVED — recent minus earlier
     * @param onResume                 whether the resume shows the skill
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TrendingSkill(
            Long skillId,
            String skill,
            String category,
            double earlierSharePercentage,
            double recentSharePercentage,
            double changeInPercentagePoints,
            boolean onResume) {
    }

    /**
     * A missing skill worth attention, with the figures that put it here.
     *
     * @param percentageOfJobs         PERCENTAGE — share of the category's postings
     * @param demandRank               DERIVED — position within the category
     * @param changeInPercentagePoints market-wide trend, null when the skill is not rising
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FocusArea(
            Long skillId,
            String skill,
            double percentageOfJobs,
            int demandRank,
            Double changeInPercentagePoints) {
    }
}
