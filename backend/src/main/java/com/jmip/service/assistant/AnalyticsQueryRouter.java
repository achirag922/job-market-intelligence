package com.jmip.service.assistant;

import com.jmip.config.AssistantProperties;
import com.jmip.dto.JobSearchCriteria;
import com.jmip.dto.JobSummaryResponse;
import com.jmip.dto.PagedResponse;
import com.jmip.dto.analytics.CategoryDemandResponse;
import com.jmip.dto.analytics.CompanyDemandResponse;
import com.jmip.dto.analytics.EntitySkillResponse;
import com.jmip.dto.analytics.LocationDemandResponse;
import com.jmip.dto.analytics.OverviewResponse;
import com.jmip.dto.analytics.SalaryRangeResponse;
import com.jmip.dto.analytics.SkillAnalyticsResponse;
import com.jmip.dto.analytics.SkillDemandResponse;
import com.jmip.dto.analytics.SkillTrendResponse;
import com.jmip.dto.assistant.ChartPoint;
import com.jmip.dto.assistant.VisualizationResponse;
import com.jmip.dto.resume.ResumeMatchResponse;
import com.jmip.dto.resume.ResumeSkillsResponse;
import com.jmip.repository.AnalyticsRepository;
import com.jmip.service.AnalyticsService;
import com.jmip.service.JobService;
import com.jmip.service.analytics.CategoryAnalyticsService;
import com.jmip.service.analytics.SalaryAnalyticsService;
import com.jmip.service.analytics.SkillAnalyticsService;
import com.jmip.service.analytics.SkillTrendService;
import com.jmip.service.resume.ResumeMatchService;
import com.jmip.service.resume.ResumeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Sends a validated intent to the service that already answers that question.
 *
 * <p>This is the narrow gate the architecture depends on. There is no query builder here
 * and no SQL: every branch is a call to a service that existed before the assistant did,
 * with arguments that have been through {@link IntentValidator}. A model cannot reach the
 * database except by causing one of these branches to be taken, and every branch is a
 * query someone already reviewed.
 *
 * <p>It also chooses the chart. That decision belongs to the backend, because it follows
 * from the shape of the data — ranked rows are bars, a series over time is a line, parts
 * of a whole are a pie — and the frontend only ever receives a type it already knows.
 */
@Component
public class AnalyticsQueryRouter {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsQueryRouter.class);

    /** Below this many postings, a trend is noise; the trend service's own default. */
    private static final int TREND_MIN_JOBS = 3;
    private static final int DEFAULT_TREND_MONTHS = 6;

    private final SkillAnalyticsService skillAnalyticsService;
    private final SkillTrendService skillTrendService;
    private final CategoryAnalyticsService categoryAnalyticsService;
    private final SalaryAnalyticsService salaryAnalyticsService;
    private final AnalyticsService analyticsService;
    private final JobService jobService;
    private final ResumeService resumeService;
    private final ResumeMatchService resumeMatchService;
    private final AnalyticsRepository analyticsRepository;
    private final AssistantProperties properties;
    private final CareerCopilotRouter careerCopilotRouter;

    public AnalyticsQueryRouter(SkillAnalyticsService skillAnalyticsService,
                                SkillTrendService skillTrendService,
                                CategoryAnalyticsService categoryAnalyticsService,
                                SalaryAnalyticsService salaryAnalyticsService,
                                AnalyticsService analyticsService,
                                JobService jobService,
                                ResumeService resumeService,
                                ResumeMatchService resumeMatchService,
                                AnalyticsRepository analyticsRepository,
                                AssistantProperties properties,
                                CareerCopilotRouter careerCopilotRouter) {
        this.skillAnalyticsService = skillAnalyticsService;
        this.skillTrendService = skillTrendService;
        this.categoryAnalyticsService = categoryAnalyticsService;
        this.salaryAnalyticsService = salaryAnalyticsService;
        this.analyticsService = analyticsService;
        this.jobService = jobService;
        this.resumeService = resumeService;
        this.resumeMatchService = resumeMatchService;
        this.analyticsRepository = analyticsRepository;
        this.properties = properties;
        this.careerCopilotRouter = careerCopilotRouter;
    }

    /**
     * @param resumeId the resume the caller selected, null when none. Only the resume
     *                 intents read it, and they are rejected earlier without one
     * @param jobId    a posting the caller named, for resume matching
     */
    public AssistantData route(ResolvedIntent intent, UUID resumeId, Long jobId) {
        long startedAt = System.nanoTime();
        AssistantData data = dispatch(intent, resumeId, jobId);
        log.info("Assistant intent {} returned {} rows in {}ms",
                intent.intent(), data.rows().size(), (System.nanoTime() - startedAt) / 1_000_000);
        return data;
    }

    /** V7.6: the signed-in user's default processed resume, for questions asked without one selected. */
    public java.util.Optional<UUID> defaultResumeId() {
        return careerCopilotRouter.defaultResumeId();
    }

    private AssistantData dispatch(ResolvedIntent intent, UUID resumeId, Long jobId) {
        ResolvedEntities entities = intent.entities();
        return switch (intent.intent()) {
            case SKILL_DEMAND -> skillDemand(entities, intent.limit());
            case SKILL_TREND -> skillTrend(entities, intent.months(), intent.limit());
            case JOB_CATEGORY_DEMAND -> categoryDemand(intent.limit());
            case COMPANY_DEMAND -> companyDemand(entities, intent.limit());
            case LOCATION_DEMAND -> locationDemand(entities, intent.limit());
            case JOB_SEARCH -> jobSearch(entities, intent.limit());
            case SALARY_ANALYSIS -> salary(entities);
            case SKILL_GAP -> skillGap(entities, resumeId, jobId, intent.limit());
            case RESUME_MATCH -> resumeMatch(resumeId, jobId);
            case SKILL_COMPARISON -> skillComparison(entities);
            case CATEGORY_COMPARISON -> categoryComparison(entities);
            case GENERAL_JOB_MARKET -> overview();
            // V7.6 copilot: the signed-in user's own data, through their owner-scoped services.
            case MY_SKILL_GAP, NEXT_SKILLS, TARGET_ROLE_SKILLS, TARGET_ROLE_DEMAND, MY_JOB_MATCHES,
                 RESUME_IMPROVEMENT, APPLICATION_PROGRESS, SAVED_JOB_PRIORITY ->
                    careerCopilotRouter.route(intent, resumeId, jobId);
            // Rejected during validation and never reaches here; the branch exists so
            // adding an intent to the enum is a compile error until it is routed.
            case UNSUPPORTED -> AssistantData.empty(null);
        };
    }

    // ----------------------------------------------------------------- skills

    /**
     * Which service answers "top skills" depends on what the question narrowed it to, and
     * the percentages differ accordingly: within a category they are a share of that
     * category, within a company a share of that company's postings.
     */
    private AssistantData skillDemand(ResolvedEntities entities, int limit) {
        if (entities.hasCategory()) {
            List<EntitySkillResponse> skills =
                    categoryAnalyticsService.skillsForCategory(entities.jobCategory(), limit);
            return AssistantData.of(skills, bar("Top skills in " + entities.jobCategory(),
                    "Skill", "Job count",
                    skills.stream().map(s -> new ChartPoint(s.skill(), s.jobCount())).toList()));
        }
        if (entities.hasCompany()) {
            List<EntitySkillResponse> skills =
                    skillAnalyticsService.skillsForCompany(entities.companyId(), limit);
            return AssistantData.of(skills, bar("Top skills at " + entities.companyName(),
                    "Skill", "Job count",
                    skills.stream().map(s -> new ChartPoint(s.skill(), s.jobCount())).toList()));
        }

        // The general path also carries location and title filters, and returns the
        // denominator its percentages were taken against.
        SkillAnalyticsResponse response = skillAnalyticsService.skillDemand(
                entities.location(), null, null, entities.title(), 0, limit);
        List<SkillDemandResponse> skills = response.skills().content();
        return AssistantData.of(skills,
                bar(scopedTitle("Top skills", entities), "Skill", "Job count",
                        skills.stream().map(s -> new ChartPoint(s.skill(), s.jobCount())).toList()));
    }

    /**
     * One skill's history.
     *
     * <p>The trend service ranks movers rather than answering about a named skill, so the
     * skill is picked out of its results here. Asking for a generous limit and filtering
     * is the reuse the rules call for; a second, nearly identical query would be the
     * duplication they forbid.
     */
    private AssistantData skillTrend(ResolvedEntities entities, Integer months, int limit) {
        int window = months == null ? DEFAULT_TREND_MONTHS : months;
        SkillTrendResponse response =
                skillTrendService.trends(window, TREND_MIN_JOBS, null, properties.maxLimit());

        List<SkillTrendResponse.SkillTrend> matching = response.trends().stream()
                .filter(trend -> trend.skill().equalsIgnoreCase(entities.skill()))
                .toList();

        if (matching.isEmpty()) {
            return AssistantData.empty("\"" + entities.skill() + "\" does not appear in enough "
                    + "postings over the last " + window + " months to show a trend.");
        }

        SkillTrendResponse.SkillTrend trend = matching.get(0);
        List<ChartPoint> points = trend.series().stream()
                .map(point -> new ChartPoint(point.period().toString(), point.sharePercentage()))
                .toList();

        return AssistantData.of(matching, VisualizationResponse.line(
                trend.skill() + " demand over time", "Month", "Share of postings (%)", points));
    }

    private AssistantData skillComparison(ResolvedEntities entities) {
        List<SkillComparisonRow> rows = List.of(
                comparisonRow(entities.skill()),
                comparisonRow(entities.secondSkill()));

        return AssistantData.of(rows, bar(
                entities.skill() + " vs " + entities.secondSkill(), "Skill", "Job count",
                rows.stream().map(row -> new ChartPoint(row.skill(), row.jobCount())).toList()));
    }

    private SkillComparisonRow comparisonRow(String skill) {
        return new SkillComparisonRow(skill, skillAnalyticsService.jobCountForSkill(skill));
    }

    // ------------------------------------------------------------- categories

    private AssistantData categoryDemand(int limit) {
        List<CategoryDemandResponse> categories = categoryAnalyticsService.categoryDistribution()
                .stream().limit(limit).toList();

        // A pie is honest here and almost nowhere else: every classified posting belongs to
        // exactly one category, so the slices genuinely make up a whole.
        return AssistantData.of(categories, VisualizationResponse.pie(
                "Postings by job category",
                categories.stream()
                        .map(row -> new ChartPoint(row.category(), row.jobCount()))
                        .toList()));
    }

    private AssistantData categoryComparison(ResolvedEntities entities) {
        List<CategoryComparisonRow> rows = List.of(
                categoryComparisonRow(entities.jobCategory()),
                categoryComparisonRow(entities.secondJobCategory()));

        return AssistantData.of(rows, bar(
                entities.jobCategory() + " vs " + entities.secondJobCategory(),
                "Job category", "Job count",
                rows.stream().map(row -> new ChartPoint(row.category(), row.jobCount())).toList()));
    }

    private CategoryComparisonRow categoryComparisonRow(String category) {
        List<String> topSkills = categoryAnalyticsService.skillsForCategory(category, 5).stream()
                .map(EntitySkillResponse::skill)
                .toList();
        return new CategoryComparisonRow(
                category, analyticsRepository.countJobsInCategory(category), topSkills);
    }

    // -------------------------------------------------------- places and firms

    private AssistantData companyDemand(ResolvedEntities entities, int limit) {
        if (entities.hasCategory()) {
            List<CompanyDemandResponse> companies =
                    categoryAnalyticsService.companiesForCategory(entities.jobCategory(), limit);
            return AssistantData.of(companies, companyChart(
                    "Companies hiring for " + entities.jobCategory(), companies));
        }
        if (entities.hasSkill()) {
            List<CompanyDemandResponse> companies =
                    skillAnalyticsService.companiesForSkill(entities.skill(), limit);
            return AssistantData.of(companies, companyChart(
                    "Companies asking for " + entities.skill(), companies));
        }

        PagedResponse<CompanyDemandResponse> page = analyticsService.companyDemand(0, limit);
        return AssistantData.of(page.content(),
                companyChart("Companies by posting count", page.content()));
    }

    private AssistantData locationDemand(ResolvedEntities entities, int limit) {
        if (entities.hasCategory()) {
            List<LocationDemandResponse> locations =
                    categoryAnalyticsService.locationsForCategory(entities.jobCategory(), limit);
            return AssistantData.of(locations, locationChart(
                    "Where " + entities.jobCategory() + " jobs are", locations));
        }
        if (entities.hasSkill()) {
            List<LocationDemandResponse> locations =
                    skillAnalyticsService.locationsForSkill(entities.skill(), limit);
            return AssistantData.of(locations, locationChart(
                    "Where " + entities.skill() + " jobs are", locations));
        }

        PagedResponse<LocationDemandResponse> page = analyticsService.locationDemand(0, limit);
        return AssistantData.of(page.content(),
                locationChart("Locations by posting count", page.content()));
    }

    // ---------------------------------------------------------------- postings

    private AssistantData jobSearch(ResolvedEntities entities, int limit) {
        JobSearchCriteria criteria = new JobSearchCriteria(
                entities.title(), entities.location(), entities.companyName(),
                entities.skill(), null, entities.jobCategory());

        PagedResponse<JobSummaryResponse> page =
                jobService.search(criteria, PageRequest.of(0, limit));

        String note = page.totalElements() > page.content().size()
                ? "Showing the first " + page.content().size() + " of "
                        + page.totalElements() + " matching postings."
                : null;

        return AssistantData.of(page.content(),
                VisualizationResponse.table("Matching job postings"), note);
    }

    // ----------------------------------------------------------------- salary

    /**
     * Salary, per currency, or an honest refusal.
     *
     * <p>The dataset holds eight currencies and no exchange rates, so there is no single
     * number to give. Thin samples are dropped by the service rather than reported with a
     * caveat nobody reads.
     */
    private AssistantData salary(ResolvedEntities entities) {
        List<SalaryRangeResponse> ranges = salaryAnalyticsService.salaryRanges(
                entities.jobCategory(), properties.minSalarySample());

        if (ranges.isEmpty()) {
            return AssistantData.empty(
                    "This dataset does not have enough salary data to answer that. Salaries are "
                            + "stated in several currencies and, for this scope, no single currency "
                            + "has at least " + properties.minSalarySample() + " postings.");
        }

        return AssistantData.of(ranges, VisualizationResponse.table(
                entities.hasCategory()
                        ? "Stated salaries for " + entities.jobCategory() + ", by currency"
                        : "Stated salaries by currency"),
                "Figures are grouped by currency and never combined: this dataset carries no "
                        + "exchange rates.");
    }

    // ----------------------------------------------------------------- resume

    /**
     * What a resume is missing.
     *
     * <p>Against a named posting this is exactly V3's match, read for its gap. Against a
     * category there is no single posting to compare with, so the category's most-asked-for
     * skills are compared with the resume's — composed from two existing services rather
     * than a second matching algorithm.
     */
    private AssistantData skillGap(ResolvedEntities entities, UUID resumeId, Long jobId, int limit) {
        if (jobId != null) {
            ResumeMatchResponse match = resumeMatchService.match(resumeId, jobId);
            return AssistantData.of(match.missingSkills(), VisualizationResponse.table(
                    "Skills missing for " + match.jobTitle()));
        }

        if (!entities.hasCategory()) {
            return AssistantData.empty("Which job category should I check your resume against?");
        }

        ResumeSkillsResponse resume = resumeService.findSkills(resumeId);
        Set<String> owned = new LinkedHashSet<>();
        resume.skills().forEach(skill -> owned.add(skill.name().toLowerCase(Locale.ROOT)));

        List<EntitySkillResponse> wanted =
                categoryAnalyticsService.skillsForCategory(entities.jobCategory(), limit);
        List<EntitySkillResponse> missing = wanted.stream()
                .filter(skill -> !owned.contains(skill.skill().toLowerCase(Locale.ROOT)))
                .toList();

        if (missing.isEmpty()) {
            return AssistantData.empty("Your resume already covers the top "
                    + wanted.size() + " skills asked for in " + entities.jobCategory() + " postings.");
        }

        return AssistantData.of(missing, bar(
                "Skills to learn for " + entities.jobCategory(), "Skill", "Job count",
                missing.stream().map(s -> new ChartPoint(s.skill(), s.jobCount())).toList()),
                "Compared against the top " + wanted.size() + " skills in that category.");
    }

    private AssistantData resumeMatch(UUID resumeId, Long jobId) {
        if (jobId == null) {
            return AssistantData.empty(
                    "Which job should I compare your resume against? Pick one in Job Explorer "
                            + "and ask again.");
        }
        ResumeMatchResponse match = resumeMatchService.match(resumeId, jobId);
        return AssistantData.of(List.of(match), VisualizationResponse.table(
                "Your resume against " + match.jobTitle()));
    }

    // ---------------------------------------------------------------- overview

    private AssistantData overview() {
        OverviewResponse overview = analyticsService.overview();
        return AssistantData.of(List.of(overview), VisualizationResponse.none());
    }

    // ----------------------------------------------------------------- charts

    /** A bar chart, unless there is nothing to draw. */
    private static VisualizationResponse bar(String title, String xAxis, String yAxis,
                                             List<ChartPoint> points) {
        return points.isEmpty()
                ? VisualizationResponse.none()
                : VisualizationResponse.bar(title, xAxis, yAxis, points);
    }

    private static VisualizationResponse companyChart(String title,
                                                      List<CompanyDemandResponse> companies) {
        return bar(title, "Company", "Job count", companies.stream()
                .map(row -> new ChartPoint(row.company().name(), row.jobCount()))
                .toList());
    }

    private static VisualizationResponse locationChart(String title,
                                                       List<LocationDemandResponse> locations) {
        return bar(title, "Location", "Job count", locations.stream()
                .map(row -> new ChartPoint(row.location().displayName(), row.jobCount()))
                .toList());
    }

    /** Names the scope in the chart title, so a filtered chart cannot be read as global. */
    private static String scopedTitle(String base, ResolvedEntities entities) {
        List<String> parts = new ArrayList<>();
        if (entities.hasTitle()) {
            parts.add("for \"" + entities.title() + "\"");
        }
        if (entities.hasLocation()) {
            parts.add("in " + entities.location());
        }
        return parts.isEmpty() ? base : base + " " + String.join(" ", parts);
    }

    /**
     * Two skills side by side.
     *
     * @param jobCount RAW COUNT — postings asking for this skill
     */
    public record SkillComparisonRow(String skill, long jobCount) {
    }

    /**
     * Two categories side by side. Facts only — counts and the skills each asks for — with
     * no judgement about which is the better thing to be.
     *
     * @param jobCount  RAW COUNT — postings in this category
     * @param topSkills the skills it most asks for, most common first
     */
    public record CategoryComparisonRow(String category, long jobCount, List<String> topSkills) {
    }
}
