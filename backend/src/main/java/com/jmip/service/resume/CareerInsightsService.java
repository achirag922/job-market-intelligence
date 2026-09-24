package com.jmip.service.resume;

import com.jmip.ai.AiFailureException;
import com.jmip.ai.AiUnavailableException;
import com.jmip.dto.SkillResponse;
import com.jmip.dto.analytics.EntitySkillResponse;
import com.jmip.dto.analytics.SkillTrendResponse.SkillTrend;
import com.jmip.dto.analytics.TrendDirection;
import com.jmip.dto.assistant.VisualizationResponse;
import com.jmip.dto.resume.CareerInsightsResponse;
import com.jmip.dto.resume.CareerInsightsResponse.CategorySource;
import com.jmip.dto.resume.CareerInsightsResponse.DemandSkill;
import com.jmip.dto.resume.CareerInsightsResponse.FocusArea;
import com.jmip.dto.resume.CareerInsightsResponse.TrendingSkill;
import com.jmip.dto.resume.ResumeRecommendationResponse;
import com.jmip.entity.Resume;
import com.jmip.entity.Skill;
import com.jmip.mapper.JobMapper;
import com.jmip.service.analytics.CategoryAnalyticsService;
import com.jmip.service.analytics.SkillTrendService;
import com.jmip.service.assistant.AnswerGenerator;
import com.jmip.service.assistant.AssistantData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sets a resume against the demand for one job category.
 *
 * <p>This class computes no statistic of its own. Category demand comes from
 * {@link CategoryAnalyticsService}, trends from {@link SkillTrendService}, and jobs from
 * the V6.3 {@link ResumeMatchService#recommend} ranking. What it adds is only set logic —
 * which of those skills the resume has — and an ordering of the gaps.
 */
@Service
public class CareerInsightsService {

    private static final Logger log = LoggerFactory.getLogger(CareerInsightsService.class);

    /** The category's top skills shown as "high demand"; gaps are drawn from these. */
    static final int HIGH_DEMAND_LIMIT = 10;
    /** A wider slice of the category, so a resume skill ranked 20th still counts as strong. */
    static final int CATEGORY_SKILL_POOL = 50;
    static final int TRENDING_LIMIT = 10;
    static final int FOCUS_AREA_LIMIT = 5;
    static final int RELATED_JOB_LIMIT = 5;
    /** Recommendations searched for jobs in the category — the V6.3 maximum. */
    static final int RECOMMENDATION_POOL = 20;

    /** The defaults of the public trends endpoint, so both report the same movement. */
    static final int TREND_MONTHS = 6;
    static final int TREND_MIN_JOBS = 3;
    private static final int TREND_FETCH_LIMIT = 100;

    private final ResumeService resumeService;
    private final ResumeMatchService resumeMatchService;
    private final CategoryAnalyticsService categoryAnalyticsService;
    private final SkillTrendService skillTrendService;
    private final AnswerGenerator answerGenerator;
    private final JobMapper jobMapper;

    public CareerInsightsService(ResumeService resumeService,
                                 ResumeMatchService resumeMatchService,
                                 CategoryAnalyticsService categoryAnalyticsService,
                                 SkillTrendService skillTrendService,
                                 AnswerGenerator answerGenerator,
                                 JobMapper jobMapper) {
        this.resumeService = resumeService;
        this.resumeMatchService = resumeMatchService;
        this.categoryAnalyticsService = categoryAnalyticsService;
        this.skillTrendService = skillTrendService;
        this.answerGenerator = answerGenerator;
        this.jobMapper = jobMapper;
    }

    /**
     * @param category       the target category, or null to use the category of the
     *                       resume's best V6.3 recommendation
     * @param includeSummary whether to ask the V5 AI layer to describe the result
     * @throws com.jmip.common.exception.ResourceNotFoundException for an unknown resume
     *                                                             or category
     * @throws ResumeNotReadyException                             for an unprocessed resume
     */
    // Not transactional on purpose: the resume's skills arrive with it, each analytics call
    // runs in its own read-only transaction, and the optional AI call must not hold a
    // database connection open while it waits on the provider.
    public CareerInsightsResponse insights(UUID resumeId, String category, boolean includeSummary) {
        Resume resume = resumeService.requireCompletedResume(resumeId);
        Set<Long> resumeSkillIds = resume.getSkills().stream().map(Skill::getId).collect(Collectors.toSet());
        List<SkillResponse> resumeSkills = resume.getSkills().stream()
                .map(jobMapper::toSkill)
                .sorted(Comparator.comparing(SkillResponse::name))
                .toList();

        List<ResumeRecommendationResponse> recommendations =
                resumeMatchService.recommend(resumeId, RECOMMENDATION_POOL);

        String target = category == null || category.isBlank() ? null : category.trim();
        CategorySource source = CategorySource.REQUESTED;
        if (target == null) {
            target = recommendations.stream()
                    .map(ResumeRecommendationResponse::jobCategory)
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst()
                    .orElse(null);
            source = CategorySource.TOP_RECOMMENDATION;
        }
        if (target == null) {
            return new CareerInsightsResponse(resumeId, null, null, resumeSkills,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                    "No target category was given and no recommended job has one to suggest");
        }

        // Unknown categories surface as a 404 from the analytics service itself.
        List<DemandSkill> categorySkills = categoryAnalyticsService
                .skillsForCategory(target, CATEGORY_SKILL_POOL).stream()
                .map(row -> toDemandSkill(row, resumeSkillIds.contains(row.skillId())))
                .toList();

        List<DemandSkill> highDemand = categorySkills.stream().limit(HIGH_DEMAND_LIMIT).toList();
        List<DemandSkill> strong = categorySkills.stream().filter(DemandSkill::onResume).toList();
        List<DemandSkill> gaps = highDemand.stream().filter(skill -> !skill.onResume()).toList();

        Set<Long> categorySkillIds = categorySkills.stream().map(DemandSkill::skillId).collect(Collectors.toSet());
        List<TrendingSkill> trending = skillTrendService
                .trends(TREND_MONTHS, TREND_MIN_JOBS, TrendDirection.RISING, TREND_FETCH_LIMIT)
                .trends().stream()
                .filter(trend -> categorySkillIds.contains(trend.skillId()))
                .limit(TRENDING_LIMIT)
                .map(trend -> toTrendingSkill(trend, resumeSkillIds.contains(trend.skillId())))
                .toList();

        List<FocusArea> focusAreas = focusAreas(gaps, trending);

        String categoryName = target;
        List<ResumeRecommendationResponse> relatedJobs = recommendations.stream()
                .filter(job -> categoryName.equals(job.jobCategory()))
                .limit(RELATED_JOB_LIMIT)
                .toList();

        log.debug("Career insights for resume {} in {}: {} strong, {} gaps, {} trending, {} jobs",
                resumeId, target, strong.size(), gaps.size(), trending.size(), relatedJobs.size());

        CareerInsightsResponse insights = new CareerInsightsResponse(resumeId, target, source, resumeSkills,
                highDemand, strong, gaps, trending, focusAreas, relatedJobs, null, null);
        return includeSummary ? withSummary(insights) : insights;
    }

    /**
     * Gaps that are also rising lead, biggest rise first; the rest follow in category
     * demand order. Both orderings come straight from the analytics figures.
     */
    static List<FocusArea> focusAreas(List<DemandSkill> gaps, List<TrendingSkill> trending) {
        Map<Long, Double> changeBySkill = trending.stream()
                .collect(Collectors.toMap(TrendingSkill::skillId, TrendingSkill::changeInPercentagePoints,
                        (first, second) -> first));

        return gaps.stream()
                .map(gap -> new FocusArea(gap.skillId(), gap.skill(), gap.percentageOfJobs(), gap.rank(),
                        changeBySkill.get(gap.skillId())))
                .sorted(Comparator
                        .comparing((FocusArea area) -> area.changeInPercentagePoints() == null)
                        .thenComparing(area -> area.changeInPercentagePoints() == null
                                ? 0.0 : -area.changeInPercentagePoints())
                        .thenComparingInt(FocusArea::demandRank))
                .limit(FOCUS_AREA_LIMIT)
                .toList();
    }

    /**
     * Hands the computed figures to the existing V5 answer generator, which is bound by its
     * prompt to repeat only what it is given. A failing or unconfigured provider leaves the
     * insights intact and says why the summary is missing.
     */
    private CareerInsightsResponse withSummary(CareerInsightsResponse insights) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("targetCategory", insights.targetCategory());
        row.put("strongSkills", names(insights.strongSkills(), DemandSkill::skill, DemandSkill::percentageOfJobs));
        row.put("skillGaps", names(insights.skillGaps(), DemandSkill::skill, DemandSkill::percentageOfJobs));
        row.put("risingSkillsMarketWide", names(insights.trendingSkills(),
                TrendingSkill::skill, TrendingSkill::changeInPercentagePoints));
        row.put("recommendedJobsInCategory", insights.recommendedJobs().size());

        String question = "How do this resume's skills compare with the skills requested in "
                + insights.targetCategory() + " postings?";
        String summary = null;
        String note = null;
        try {
            summary = answerGenerator.describe(question,
                    AssistantData.of(List.of(row), VisualizationResponse.none(),
                            "Percentages under strongSkills and skillGaps are shares of the category's "
                                    + "postings; risingSkillsMarketWide values are percentage-point changes"));
        } catch (AiUnavailableException | AiFailureException failure) {
            log.warn("Career insight summary unavailable: {}", failure.getMessage());
            note = "The AI summary is unavailable right now; the figures below are unaffected";
        }

        return new CareerInsightsResponse(insights.resumeId(), insights.targetCategory(), insights.categorySource(),
                insights.resumeSkills(), insights.highDemandSkills(), insights.strongSkills(), insights.skillGaps(),
                insights.trendingSkills(), insights.focusAreas(), insights.recommendedJobs(), summary, note);
    }

    private static <T> Map<String, Double> names(List<T> items, Function<T, String> name, Function<T, Double> value) {
        Map<String, Double> result = new LinkedHashMap<>();
        items.forEach(item -> result.put(name.apply(item), value.apply(item)));
        return result;
    }

    private static DemandSkill toDemandSkill(EntitySkillResponse row, boolean onResume) {
        return new DemandSkill(row.skillId(), row.skill(), row.category(), row.jobCount(),
                row.percentageOfJobs(), row.rank(), onResume);
    }

    private static TrendingSkill toTrendingSkill(SkillTrend trend, boolean onResume) {
        return new TrendingSkill(trend.skillId(), trend.skill(), trend.category(),
                trend.earlierSharePercentage(), trend.recentSharePercentage(),
                trend.changeInPercentagePoints(), onResume);
    }
}
