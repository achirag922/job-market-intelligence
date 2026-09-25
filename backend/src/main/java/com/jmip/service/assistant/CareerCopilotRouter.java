package com.jmip.service.assistant;

import com.jmip.dto.assistant.ChartPoint;
import com.jmip.dto.assistant.VisualizationResponse;
import com.jmip.dto.career.CareerGoalResponse;
import com.jmip.dto.career.RoadmapResponse;
import com.jmip.dto.career.RoadmapResponse.RoadmapSkill;
import com.jmip.dto.market.MarketResponses;
import com.jmip.dto.resume.CareerInsightsResponse;
import com.jmip.dto.resume.ResumeJobAnalysisResponse;
import com.jmip.dto.resume.ResumeMatchResponse;
import com.jmip.dto.resume.ResumeRecommendationResponse;
import com.jmip.dto.resume.ResumeResponse;
import com.jmip.dto.saved.SavedJobResponse;
import com.jmip.entity.ApplicationStatus;
import com.jmip.entity.CareerGoalStatus;
import com.jmip.entity.SkillProgressStatus;
import com.jmip.service.career.CareerGoalService;
import com.jmip.service.career.RoadmapService;
import com.jmip.service.market.MarketIntelligenceService;
import com.jmip.service.resume.CareerInsightsService;
import com.jmip.service.resume.ResumeAnalysisService;
import com.jmip.service.resume.ResumeMatchService;
import com.jmip.service.resume.ResumeService;
import com.jmip.service.saved.SavedJobService;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * V7.6: the career-copilot intents, each mapped to a service that already exists. Every one
 * of those services takes the account from the session, so nothing here can reach another
 * user's resume, goals or applications: there is no user id to pass, and a resume id from the
 * request is checked for ownership by {@link ResumeService} as everywhere else.
 *
 * <p>Rows carry names and figures only. Free text the user wrote (application notes) and
 * posting descriptions stay out, so nothing stored can reach the answer model as instructions.
 */
@Component
public class CareerCopilotRouter {

    /** Saved jobs checked against the resume; each check is one match computation. */
    static final int SAVED_JOBS_CHECKED = 20;
    static final int NEXT_SKILLS_SHOWN = 5;

    static final String NO_GOAL = "You have no active career goal yet. Create one under Career Goals, "
            + "then ask again.";
    static final String NO_ROLE = "Which job category is your target role? Name it in the question, or create "
            + "a career goal under Career Goals.";

    private final CareerGoalService careerGoalService;
    private final RoadmapService roadmapService;
    private final ResumeService resumeService;
    private final ResumeMatchService resumeMatchService;
    private final ResumeAnalysisService resumeAnalysisService;
    private final CareerInsightsService careerInsightsService;
    private final SavedJobService savedJobService;
    private final MarketIntelligenceService marketIntelligenceService;

    public CareerCopilotRouter(CareerGoalService careerGoalService, RoadmapService roadmapService,
                               ResumeService resumeService, ResumeMatchService resumeMatchService,
                               ResumeAnalysisService resumeAnalysisService, CareerInsightsService careerInsightsService,
                               SavedJobService savedJobService, MarketIntelligenceService marketIntelligenceService) {
        this.careerGoalService = careerGoalService;
        this.roadmapService = roadmapService;
        this.resumeService = resumeService;
        this.resumeMatchService = resumeMatchService;
        this.resumeAnalysisService = resumeAnalysisService;
        this.careerInsightsService = careerInsightsService;
        this.savedJobService = savedJobService;
        this.marketIntelligenceService = marketIntelligenceService;
    }

    /** The signed-in user's default resume if it is processed, else their newest processed one. */
    public Optional<UUID> defaultResumeId() {
        List<ResumeResponse> completed = resumeService.list().stream()
                .filter(resume -> "COMPLETED".equals(resume.status()))
                .toList();
        return completed.stream().filter(ResumeResponse::isDefault).findFirst()
                .or(() -> completed.stream().findFirst())
                .map(ResumeResponse::id);
    }

    public AssistantData route(ResolvedIntent intent, UUID resumeId, Long jobId) {
        ResolvedEntities entities = intent.entities();
        return switch (intent.intent()) {
            case MY_SKILL_GAP -> mySkillGap(resumeId);
            case NEXT_SKILLS -> nextSkills(resumeId);
            case TARGET_ROLE_SKILLS -> targetRoleSkills(entities);
            case TARGET_ROLE_DEMAND -> targetRoleDemand(entities);
            case MY_JOB_MATCHES -> myJobMatches(resumeId, intent.limit());
            case RESUME_IMPROVEMENT -> resumeImprovement(resumeId, jobId);
            case APPLICATION_PROGRESS -> applicationProgress();
            case SAVED_JOB_PRIORITY -> savedJobPriority(resumeId);
            default -> throw new IllegalArgumentException("Not a copilot intent: " + intent.intent());
        };
    }

    // ------------------------------------------------------------------ goals and roadmap

    private AssistantData mySkillGap(UUID resumeId) {
        Optional<CareerGoalResponse> goal = activeGoal();
        if (goal.isEmpty()) {
            return AssistantData.empty(NO_GOAL);
        }
        RoadmapResponse roadmap = roadmapService.roadmap(goal.get().id(), resumeId);
        List<RoadmapRow> rows = roadmap.roadmap().stream().map(RoadmapRow::of).toList();
        String context = "For your goal \"" + roadmap.targetRole() + "\" (" + roadmap.targetCategory() + ")"
                + (roadmap.basedOnResume() == null ? ", with no processed resume." : ", against your resume \""
                + roadmap.basedOnResume().title() + "\".");
        if (rows.isEmpty()) {
            return AssistantData.empty(context + " Your resume already covers every skill on the roadmap.");
        }
        return AssistantData.of(rows, bar("Skills missing for " + roadmap.targetRole(), "Skill", "% of postings",
                roadmap.roadmap().stream().filter(skill -> skill.percentageOfJobs() != null)
                        .map(skill -> new ChartPoint(skill.skill(), skill.percentageOfJobs())).toList()), context);
    }

    private AssistantData nextSkills(UUID resumeId) {
        Optional<CareerGoalResponse> goal = activeGoal();
        if (goal.isPresent()) {
            RoadmapResponse roadmap = roadmapService.roadmap(goal.get().id(), resumeId);
            List<RoadmapRow> next = roadmap.roadmap().stream()
                    .filter(skill -> skill.status() != SkillProgressStatus.COMPLETED)
                    .limit(NEXT_SKILLS_SHOWN)
                    .map(RoadmapRow::of)
                    .toList();
            if (next.isEmpty()) {
                return AssistantData.empty("Every skill on the roadmap for \"" + roadmap.targetRole()
                        + "\" is on your resume or marked completed.");
            }
            return AssistantData.of(next, VisualizationResponse.table("Next skills for " + roadmap.targetRole()),
                    "The first roadmap skills you have not completed, in the roadmap's priority order.");
        }
        Optional<UUID> resume = resumeId != null ? Optional.of(resumeId) : defaultResumeId();
        if (resume.isEmpty()) {
            return AssistantData.empty(NO_GOAL);
        }
        CareerInsightsResponse insights = careerInsightsService.insights(resume.get(), null, false);
        if (insights.focusAreas().isEmpty()) {
            return AssistantData.empty(insights.note() != null ? insights.note()
                    : "There are no focus areas for your resume in the current data.");
        }
        return AssistantData.of(insights.focusAreas(), VisualizationResponse.table("Focus areas for " + insights.targetCategory()),
                "You have no active career goal, so these are your resume's focus areas for "
                        + insights.targetCategory() + ", the category of your best-matching jobs.");
    }

    // ------------------------------------------------------------------ market for the target role

    private AssistantData targetRoleSkills(ResolvedEntities entities) {
        Optional<String> category = targetCategory(entities);
        if (category.isEmpty()) {
            return AssistantData.empty(NO_ROLE);
        }
        MarketResponses.SkillResponse skills = marketIntelligenceService.skills(
                marketIntelligenceService.filter(category.get(), null, null, null), null);
        if (skills.topSkills().isEmpty()) {
            return AssistantData.empty("No postings with skills were found for " + category.get() + ".");
        }
        return AssistantData.of(skills.topSkills(), bar("Most requested skills in " + category.get(), "Skill", "% of postings",
                skills.topSkills().stream().map(row -> new ChartPoint(row.skill(), row.percentageOfPostings())).toList()),
                "Counted from " + skills.scope().postings() + " " + category.get() + " postings.");
    }

    private AssistantData targetRoleDemand(ResolvedEntities entities) {
        Optional<String> category = targetCategory(entities);
        if (category.isEmpty()) {
            return AssistantData.empty(NO_ROLE);
        }
        MarketResponses.RemoteResponse market = marketIntelligenceService.remote(
                marketIntelligenceService.filter(category.get(), null, null, null));
        List<MonthRow> rows = market.trend().stream().map(point -> new MonthRow(point.month().toString(), point.total())).toList();
        if (rows.size() < 2) {
            return AssistantData.of(rows, VisualizationResponse.table("Postings per month in " + category.get()),
                    "There is not enough dated history to show a change for " + category.get() + ".");
        }
        return AssistantData.of(rows, VisualizationResponse.line("Postings per posting month in " + category.get(), "Month",
                        "Postings", rows.stream().map(row -> new ChartPoint(row.month(), row.postings())).toList()),
                "Past posting months in the dataset, not a forecast.");
    }

    // ------------------------------------------------------------------ resume

    private AssistantData myJobMatches(UUID resumeId, int limit) {
        List<MatchRow> rows = resumeMatchService.recommend(resumeId, limit).stream().map(MatchRow::of).toList();
        if (rows.isEmpty()) {
            return AssistantData.empty("No posting shares a skill with your resume yet.");
        }
        return AssistantData.of(rows, bar("Jobs matching your resume", "Job", "Skill match %",
                rows.stream().map(row -> new ChartPoint(row.jobTitle() + " · " + row.companyName(), row.matchPercentage())).toList()),
                "Ranked by the share of each posting's listed skills that your resume covers.");
    }

    private AssistantData resumeImprovement(UUID resumeId, Long jobId) {
        if (jobId != null) {
            ResumeJobAnalysisResponse analysis = resumeAnalysisService.analyzeJob(resumeId, jobId);
            List<SuggestionRow> rows = analysis.suggestions().stream().map(SuggestionRow::new).toList();
            String summary = analysis.matchPercentage() == null ? analysis.matchNote()
                    : "Your resume covers " + analysis.matchedSkillCount() + " of " + analysis.totalJobSkills()
                    + " skills listed for " + analysis.jobTitle() + " (" + analysis.matchPercentage() + "%). Missing: "
                    + (analysis.missingSkills().isEmpty() ? "none" : String.join(", ",
                    analysis.missingSkills().stream().map(skill -> skill.name()).toList())) + ".";
            return AssistantData.of(rows, VisualizationResponse.table("Suggestions for " + analysis.jobTitle()), summary);
        }
        String category = activeGoal().map(CareerGoalResponse::targetCategory).orElse(null);
        CareerInsightsResponse insights = careerInsightsService.insights(resumeId, category, false);
        if (insights.skillGaps().isEmpty()) {
            return AssistantData.empty(insights.note() != null ? insights.note()
                    : "Your resume already lists the most requested skills in " + insights.targetCategory() + ".");
        }
        return AssistantData.of(insights.skillGaps(), bar("Requested in " + insights.targetCategory() + " but not on your resume",
                        "Skill", "% of postings",
                        insights.skillGaps().stream().map(gap -> new ChartPoint(gap.skill(), gap.percentageOfJobs())).toList()),
                (category != null ? "For your goal's category, " : "For your best-matching category, ")
                        + insights.targetCategory() + ". Pick a job to see suggestions for that posting.");
    }

    // ------------------------------------------------------------------ applications

    private AssistantData applicationProgress() {
        List<SavedJobResponse> saved = savedJobService.list(null);
        if (saved.isEmpty()) {
            return AssistantData.empty("You have no saved jobs yet. Use Save on a job to start tracking it.");
        }
        Map<ApplicationStatus, Long> counts = new EnumMap<>(ApplicationStatus.class);
        Arrays.stream(ApplicationStatus.values()).forEach(status -> counts.put(status, 0L));
        saved.forEach(job -> counts.merge(job.status(), 1L, Long::sum));
        List<StatusRow> rows = counts.entrySet().stream().map(entry -> new StatusRow(entry.getKey(), entry.getValue())).toList();
        return AssistantData.of(rows, VisualizationResponse.pie("Your saved jobs by status",
                        rows.stream().filter(row -> row.jobs() > 0).map(row -> new ChartPoint(row.status().name(), row.jobs())).toList()),
                saved.size() + " saved jobs in total.");
    }

    private AssistantData savedJobPriority(UUID resumeId) {
        List<SavedJobResponse> open = savedJobService.list(null).stream()
                .filter(job -> job.status() != ApplicationStatus.REJECTED && job.status() != ApplicationStatus.WITHDRAWN)
                .limit(SAVED_JOBS_CHECKED)
                .toList();
        if (open.isEmpty()) {
            return AssistantData.empty("You have no open saved jobs. Save a job, or move one back from rejected or withdrawn.");
        }
        Optional<UUID> resume = resumeId != null ? Optional.of(resumeId) : defaultResumeId();
        if (resume.isEmpty()) {
            return AssistantData.of(open.stream().map(job -> SavedRow.of(job, null)).toList(),
                    VisualizationResponse.table("Your open saved jobs"),
                    "Most recently updated first. Upload a resume to see how much of each posting it covers.");
        }
        List<SavedRow> rows = open.stream()
                .map(job -> SavedRow.of(job, resumeMatchService.match(resume.get(), job.job().id())))
                .sorted(Comparator.comparing((SavedRow row) -> row.matchPercentage() == null ? -1 : row.matchPercentage())
                        .reversed())
                .toList();
        return AssistantData.of(rows, VisualizationResponse.table("Your open saved jobs by skill match"),
                "Ordered by the share of each posting's listed skills your resume covers. This is information to help "
                        + "you decide, not a recommendation or a prediction.");
    }

    // ------------------------------------------------------------------ helpers

    /** The most recently changed active goal. */
    private Optional<CareerGoalResponse> activeGoal() {
        return careerGoalService.list(CareerGoalStatus.ACTIVE).stream().findFirst();
    }

    /** A category named in the question wins; otherwise the active goal's. */
    private Optional<String> targetCategory(ResolvedEntities entities) {
        if (entities.hasCategory()) {
            return Optional.of(entities.jobCategory());
        }
        return activeGoal().map(CareerGoalResponse::targetCategory);
    }

    private static VisualizationResponse bar(String title, String xAxis, String yAxis, List<ChartPoint> points) {
        return points.isEmpty() ? VisualizationResponse.table(title) : VisualizationResponse.bar(title, xAxis, yAxis, points);
    }

    // ------------------------------------------------------------------ rows

    record RoadmapRow(int priority, String skill, String source, Double percentageOfJobs, String reason,
                      SkillProgressStatus status) {
        static RoadmapRow of(RoadmapSkill skill) {
            return new RoadmapRow(skill.priority(), skill.skill(), skill.source().name(), skill.percentageOfJobs(),
                    skill.reason(), skill.status());
        }
    }

    record MonthRow(String month, long postings) {
    }

    record MatchRow(Long jobId, String jobTitle, String companyName, String jobCategory, double matchPercentage,
                    int matchedSkills, int missingSkills) {
        static MatchRow of(ResumeRecommendationResponse job) {
            return new MatchRow(job.jobId(), job.jobTitle(), job.companyName(), job.jobCategory(), job.matchPercentage(),
                    job.matchedSkills().size(), job.missingSkills().size());
        }
    }

    record SuggestionRow(String suggestion) {
    }

    record StatusRow(ApplicationStatus status, long jobs) {
    }

    /** No notes: they are the user's free text, and stay out of anything sent to the model. */
    record SavedRow(Long jobId, String jobTitle, String companyName, ApplicationStatus status, String appliedAt,
                    Double matchPercentage, Integer missingSkills) {
        static SavedRow of(SavedJobResponse saved, ResumeMatchResponse match) {
            return new SavedRow(saved.job().id(), saved.job().title(), saved.job().company().name(), saved.status(),
                    saved.appliedAt() == null ? null : saved.appliedAt().toLocalDate().toString(),
                    match == null ? null : match.matchPercentage(), match == null ? null : match.missingSkillCount());
        }
    }
}
