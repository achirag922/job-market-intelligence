package com.jmip.service.dashboard;

import com.jmip.dto.career.CareerGoalResponse;
import com.jmip.dto.career.RoadmapResponse;
import com.jmip.dto.career.RoadmapResponse.RoadmapSkill;
import com.jmip.dto.dashboard.DashboardResponse;
import com.jmip.dto.dashboard.DashboardResponse.ApplicationSection;
import com.jmip.dto.dashboard.DashboardResponse.FunnelStage;
import com.jmip.dto.dashboard.DashboardResponse.GoalSection;
import com.jmip.dto.dashboard.DashboardResponse.MarketSection;
import com.jmip.dto.dashboard.DashboardResponse.MatchSummary;
import com.jmip.dto.dashboard.DashboardResponse.PrioritySkill;
import com.jmip.dto.dashboard.DashboardResponse.RecentApplication;
import com.jmip.dto.dashboard.DashboardResponse.RecommendationSection;
import com.jmip.dto.dashboard.DashboardResponse.RecommendedJob;
import com.jmip.dto.dashboard.DashboardResponse.ResumeSection;
import com.jmip.dto.dashboard.DashboardResponse.ResumeSummary;
import com.jmip.dto.dashboard.DashboardResponse.SkillSection;
import com.jmip.dto.market.MarketResponses;
import com.jmip.dto.resume.ResumeRecommendationResponse;
import com.jmip.dto.resume.ResumeResponse;
import com.jmip.dto.saved.SavedJobResponse;
import com.jmip.entity.ApplicationStatus;
import com.jmip.entity.CareerGoalStatus;
import com.jmip.entity.SkillProgressStatus;
import com.jmip.service.career.CareerGoalService;
import com.jmip.service.career.RoadmapService;
import com.jmip.service.market.MarketFilter;
import com.jmip.service.market.MarketIntelligenceService;
import com.jmip.service.resume.ResumeMatchService;
import com.jmip.service.resume.ResumeService;
import com.jmip.service.saved.SavedJobService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * V7.7: the personal career dashboard, assembled from the services that already own each
 * figure. Nothing is calculated twice: recommendations are V6.3's, the roadmap and its
 * progress V7.4's, statuses V7.2's and the market views V7.5's. Every personal service reads
 * the account from the session, so the dashboard can only ever show the caller's own data.
 */
@Service
@Transactional(readOnly = true)
public class DashboardService {

    static final int RECOMMENDATIONS = 10;
    static final int TOP_JOBS = 5;
    static final int RECENT_APPLICATIONS = 5;
    static final int TOP_MISSING = 5;
    static final int MARKET_ROWS = 5;
    static final int SALARY_CURRENCIES = 3;
    static final List<ApplicationStatus> FUNNEL = List.of(
            ApplicationStatus.SAVED, ApplicationStatus.APPLIED, ApplicationStatus.INTERVIEW, ApplicationStatus.OFFER);

    private final ResumeService resumeService;
    private final ResumeMatchService resumeMatchService;
    private final CareerGoalService careerGoalService;
    private final RoadmapService roadmapService;
    private final SavedJobService savedJobService;
    private final MarketIntelligenceService marketIntelligenceService;

    public DashboardService(ResumeService resumeService, ResumeMatchService resumeMatchService,
                            CareerGoalService careerGoalService, RoadmapService roadmapService,
                            SavedJobService savedJobService, MarketIntelligenceService marketIntelligenceService) {
        this.resumeService = resumeService;
        this.resumeMatchService = resumeMatchService;
        this.careerGoalService = careerGoalService;
        this.roadmapService = roadmapService;
        this.savedJobService = savedJobService;
        this.marketIntelligenceService = marketIntelligenceService;
    }

    /**
     * @param goalId one of the caller's goals to show, or null for the most recently changed active goal;
     *               another account's goal is a 404, as everywhere
     */
    public DashboardResponse dashboard(UUID goalId) {
        List<ResumeResponse> resumes = resumeService.list();
        Optional<ResumeResponse> current = ResumeService.currentProcessed(resumes);
        List<CareerGoalResponse> activeGoals = careerGoalService.list(CareerGoalStatus.ACTIVE);
        Optional<CareerGoalResponse> goal = goalId != null
                ? Optional.of(careerGoalService.get(goalId))
                : activeGoals.stream().findFirst();
        Optional<RoadmapResponse> roadmap = goal.map(g -> roadmapService.roadmap(g.id(), current.map(ResumeResponse::id).orElse(null)));
        List<ResumeRecommendationResponse> recommendations = current
                .map(resume -> resumeMatchService.recommend(resume.id(), RECOMMENDATIONS))
                .orElse(List.of());

        return new DashboardResponse(
                resumeSection(resumes, current, recommendations, roadmap),
                skillSection(current, roadmap),
                recommendationSection(current, recommendations),
                applicationSection(savedJobService.list(null)),
                goalSection(goal, activeGoals.size(), roadmap),
                marketSection(goal.map(CareerGoalResponse::targetCategory).orElse(null)));
    }

    // ------------------------------------------------------------------ sections

    private static ResumeSection resumeSection(List<ResumeResponse> resumes, Optional<ResumeResponse> current,
                                               List<ResumeRecommendationResponse> recommendations,
                                               Optional<RoadmapResponse> roadmap) {
        if (current.isEmpty()) {
            return new ResumeSection(false, null, resumes.size(), null, List.of(), resumes.isEmpty()
                    ? "Upload a resume in Resume Intelligence to see your skills and matches."
                    : "None of your resumes has been processed yet.");
        }
        ResumeResponse resume = current.get();
        List<String> missing = roadmap.map(r -> r.roadmap().stream().map(RoadmapSkill::skill).limit(TOP_MISSING).toList())
                .orElse(List.of());
        return new ResumeSection(true,
                new ResumeSummary(resume.id(), resume.title(), resume.versionLabel(), resume.isDefault(),
                        resume.skills().size(), resume.uploadedAt()),
                resumes.size(), matchSummary(recommendations), missing,
                roadmap.isEmpty() ? "Create a career goal to see the skills it is missing." : null);
    }

    private static SkillSection skillSection(Optional<ResumeResponse> current, Optional<RoadmapResponse> roadmap) {
        var skills = current.map(ResumeResponse::skills).orElse(List.of());
        if (roadmap.isEmpty()) {
            return new SkillSection(current.isPresent(), skills, List.of(), List.of(), 0, null,
                    "Create a career goal to track the skills you are developing.");
        }
        List<RoadmapSkill> items = roadmap.get().roadmap();
        return new SkillSection(true, skills,
                names(items, SkillProgressStatus.IN_PROGRESS), names(items, SkillProgressStatus.COMPLETED),
                (int) items.stream().filter(item -> item.status() == SkillProgressStatus.NOT_STARTED).count(),
                roadmap.get().progress().percentComplete(), null);
    }

    private static RecommendationSection recommendationSection(Optional<ResumeResponse> current,
                                                               List<ResumeRecommendationResponse> recommendations) {
        if (current.isEmpty()) {
            return new RecommendationSection(false, 0, null, List.of(), "Upload a resume to get job recommendations.");
        }
        List<RecommendedJob> top = recommendations.stream().limit(TOP_JOBS)
                .map(job -> new RecommendedJob(job.jobId(), job.jobTitle(), job.companyName(), job.jobCategory(),
                        job.matchPercentage()))
                .toList();
        return new RecommendationSection(!recommendations.isEmpty(), recommendations.size(),
                matchSummary(recommendations).averageMatchPercentage(), top,
                recommendations.isEmpty() ? "No posting shares a skill with your resume yet." : null);
    }

    static ApplicationSection applicationSection(List<SavedJobResponse> saved) {
        Map<ApplicationStatus, Long> counts = new EnumMap<>(ApplicationStatus.class);
        for (ApplicationStatus status : ApplicationStatus.values()) {
            counts.put(status, 0L);
        }
        saved.forEach(job -> counts.merge(job.status(), 1L, Long::sum));
        List<RecentApplication> recent = saved.stream().limit(RECENT_APPLICATIONS)
                .map(job -> new RecentApplication(job.id(), job.job().id(), job.job().title(), job.job().company().name(),
                        job.status(), job.appliedAt(), job.updatedAt()))
                .toList();
        return new ApplicationSection(!saved.isEmpty(), saved.size(),
                counts.get(ApplicationStatus.SAVED), counts.get(ApplicationStatus.APPLIED),
                counts.get(ApplicationStatus.INTERVIEW), counts.get(ApplicationStatus.OFFER),
                counts.get(ApplicationStatus.REJECTED), counts.get(ApplicationStatus.WITHDRAWN),
                FUNNEL.stream().map(stage -> new FunnelStage(stage, counts.get(stage))).toList(),
                recent, saved.isEmpty() ? "Save jobs from the Job Explorer to track your applications here." : null);
    }

    private static GoalSection goalSection(Optional<CareerGoalResponse> goal, int activeGoals,
                                           Optional<RoadmapResponse> roadmap) {
        if (goal.isEmpty() || roadmap.isEmpty()) {
            return new GoalSection(false, null, null, null, activeGoals, null, List.of(), null,
                    "Create a career goal to see a skill roadmap and your progress.");
        }
        RoadmapResponse map = roadmap.get();
        List<PrioritySkill> top = map.roadmap().stream()
                .filter(item -> item.status() != SkillProgressStatus.COMPLETED)
                .limit(TOP_MISSING)
                .map(item -> new PrioritySkill(item.priority(), item.skill(), item.reason(), item.status()))
                .toList();
        return new GoalSection(true, goal.get().id(), goal.get().targetRole(), goal.get().targetCategory(), activeGoals,
                map.progress(), top, map.basedOnResume() == null ? null : map.basedOnResume().title(), map.note());
    }

    private MarketSection marketSection(String category) {
        MarketFilter filter = marketIntelligenceService.filter(category, null, null, null);
        MarketResponses.SkillResponse skills = marketIntelligenceService.skills(filter, null);
        MarketResponses.LocationResponse locations = marketIntelligenceService.locations(filter);
        MarketResponses.RemoteResponse remote = marketIntelligenceService.remote(filter);
        MarketResponses.SalaryResponse salary = marketIntelligenceService.salary(filter);
        MarketResponses.CompanyResponse companies = marketIntelligenceService.companies(filter);

        LinkedHashSet<String> notes = new LinkedHashSet<>();
        if (category == null) {
            notes.add("No active career goal, so this shows all postings.");
        }
        if (skills.scope().postings() == 0) {
            notes.add(category == null ? "There are no postings in the dataset yet."
                    : "There are no postings for " + category + " in the dataset right now.");
            return new MarketSection(false, category, skills.scope(), List.of(), null, List.of(), 0, List.of(), List.of(),
                    List.of(), new ArrayList<>(notes));
        }
        // The skill view's own notes speak of the Market page's filters; the dashboard has none.
        if (skills.trend() == null) {
            notes.add("Skill-demand trends are kept across all postings, not per category; see Skill Trends.");
        } else if (skills.trend().trends().isEmpty()) {
            notes.add("The stored skill history has too few months to show a trend yet.");
        }
        for (List<String> section : List.of(salary.notes(), remote.notes(), companies.notes())) {
            notes.addAll(section);
        }
        return new MarketSection(true, category, skills.scope(),
                skills.topSkills().stream().limit(MARKET_ROWS).toList(),
                skills.trend(),
                locations.topLocations().stream().limit(MARKET_ROWS).toList(),
                locations.locationNotStated(),
                remote.distribution(),
                salary.byCurrency().stream().limit(SALARY_CURRENCIES).toList(),
                companies.topCompanies().stream().limit(MARKET_ROWS).toList(),
                new ArrayList<>(notes));
    }

    // ------------------------------------------------------------------ helpers

    /** Best and mean match across the recommendations; no figures when there are none. */
    static MatchSummary matchSummary(List<ResumeRecommendationResponse> recommendations) {
        if (recommendations.isEmpty()) {
            return new MatchSummary(0, null, null);
        }
        double top = recommendations.stream().mapToDouble(ResumeRecommendationResponse::matchPercentage).max().orElse(0);
        double average = recommendations.stream().mapToDouble(ResumeRecommendationResponse::matchPercentage).average().orElse(0);
        return new MatchSummary(recommendations.size(), top, Math.round(average * 10.0) / 10.0);
    }

    private static List<String> names(List<RoadmapSkill> items, SkillProgressStatus status) {
        return items.stream().filter(item -> item.status() == status).map(RoadmapSkill::skill).toList();
    }
}
