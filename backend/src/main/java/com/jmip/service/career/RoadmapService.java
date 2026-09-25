package com.jmip.service.career;

import com.jmip.common.exception.InvalidRequestException;
import com.jmip.dto.SkillResponse;
import com.jmip.dto.analytics.EntitySkillResponse;
import com.jmip.dto.analytics.SkillTrendResponse.SkillTrend;
import com.jmip.dto.career.RoadmapResponse;
import com.jmip.dto.career.RoadmapResponse.MarketSkill;
import com.jmip.dto.career.RoadmapResponse.Progress;
import com.jmip.dto.career.RoadmapResponse.RoadmapSkill;
import com.jmip.dto.career.RoadmapResponse.Source;
import com.jmip.dto.resume.CareerInsightsResponse.FocusArea;
import com.jmip.entity.CareerGoal;
import com.jmip.entity.CareerGoalSkillProgress;
import com.jmip.entity.Resume;
import com.jmip.entity.Skill;
import com.jmip.entity.SkillProgressStatus;
import com.jmip.mapper.JobMapper;
import com.jmip.repository.AnalyticsRepository;
import com.jmip.repository.CareerGoalSkillProgressRepository;
import com.jmip.repository.ResumeRepository;
import com.jmip.service.analytics.CategoryAnalyticsService;
import com.jmip.service.auth.CurrentUser;
import com.jmip.service.resume.CareerInsightsService;
import com.jmip.service.resume.ResumeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * V7.4: a career goal's skill roadmap, computed on request from data JMIP already holds.
 *
 * <ul>
 *   <li>Market skills: the V4 category analytics for the goal's category — the same figures
 *       as Job Categories and career insights, never estimated.</li>
 *   <li>Current skills: the account's default resume (V7.3), or the one asked for.</li>
 *   <li>Order: the V6.4 focus-area rule, reused as is — rising skills first, then by demand.</li>
 * </ul>
 *
 * <p>No stages: nothing in the data says how advanced a skill is, so none is invented.
 */
@Service
public class RoadmapService {

    /** The category's top skills, the same size as career insights' "high demand" list. */
    static final int MARKET_SKILLS = 10;

    static final String NO_STAGES_NOTE = "Skills are listed by priority rather than in difficulty stages: "
            + "JMIP has no data on how advanced a skill is. Priority follows market data only.";

    private final CareerGoalService goalService;
    private final CareerGoalSkillProgressRepository progressRepository;
    private final ResumeRepository resumeRepository;
    private final ResumeService resumeService;
    private final CategoryAnalyticsService categoryAnalyticsService;
    private final AnalyticsRepository analyticsRepository;
    private final CareerInsightsService careerInsightsService;
    private final JobMapper jobMapper;
    private final CurrentUser currentUser;
    private final Clock clock;

    public RoadmapService(CareerGoalService goalService, CareerGoalSkillProgressRepository progressRepository,
                          ResumeRepository resumeRepository, ResumeService resumeService,
                          CategoryAnalyticsService categoryAnalyticsService, AnalyticsRepository analyticsRepository,
                          CareerInsightsService careerInsightsService, JobMapper jobMapper, CurrentUser currentUser,
                          Clock clock) {
        this.goalService = goalService;
        this.progressRepository = progressRepository;
        this.resumeRepository = resumeRepository;
        this.resumeService = resumeService;
        this.categoryAnalyticsService = categoryAnalyticsService;
        this.analyticsRepository = analyticsRepository;
        this.careerInsightsService = careerInsightsService;
        this.jobMapper = jobMapper;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /**
     * @param resumeId the resume to count as current, or null for the account's default
     */
    @Transactional(readOnly = true)
    public RoadmapResponse roadmap(UUID goalId, UUID resumeId) {
        CareerGoal goal = goalService.requireOwn(goalId);
        Resume resume = resumeId != null ? resumeService.requireCompletedResume(resumeId) : currentResume();
        Set<Long> onResume = resume == null ? Set.of()
                : resume.getSkills().stream().map(Skill::getId).collect(Collectors.toSet());

        List<EntitySkillResponse> market = marketSkills(goal.getTargetCategory());
        Map<Long, Double> rising = careerInsightsService.risingTrends().stream()
                .collect(Collectors.toMap(SkillTrend::skillId, SkillTrend::changeInPercentagePoints, (a, b) -> a));
        Map<Long, SkillProgressStatus> progress = progressRepository.findByGoalId(goalId).stream()
                .collect(Collectors.toMap(CareerGoalSkillProgress::getSkillId, CareerGoalSkillProgress::getStatus));

        List<SkillResponse> covered = new ArrayList<>();
        // Keyed by skill id, market skills first, so a chosen skill that is also in demand appears once.
        Map<Long, Candidate> toDevelop = new LinkedHashMap<>();
        for (EntitySkillResponse row : market) {
            SkillResponse skill = new SkillResponse(row.skillId(), row.skill(), row.category());
            if (onResume.contains(row.skillId())) {
                covered.add(skill);
            } else {
                toDevelop.put(row.skillId(), new Candidate(skill, Source.MARKET_DEMAND, row.percentageOfJobs(), row.rank()));
            }
        }
        Set<Long> marketIds = market.stream().map(EntitySkillResponse::skillId).collect(Collectors.toSet());
        for (Skill chosen : goal.getTargetSkills()) {
            if (marketIds.contains(chosen.getId())) {
                continue;
            }
            SkillResponse skill = jobMapper.toSkill(chosen);
            if (onResume.contains(chosen.getId())) {
                covered.add(skill);
            } else {
                toDevelop.putIfAbsent(chosen.getId(), new Candidate(skill, Source.YOUR_CHOICE, null, null));
            }
        }

        List<RoadmapSkill> roadmap = prioritise(toDevelop, rising, progress, goal.getTargetCategory());
        return new RoadmapResponse(
                goal.getId(),
                goal.getTargetRole(),
                goal.getTargetCategory(),
                resume == null ? null : new RoadmapResponse.ResumeRef(resume.getId(), resume.getTitle()),
                resume == null ? List.of() : sortedSkills(resume.getSkills()),
                market.stream().map(row -> new MarketSkill(row.skillId(), row.skill(), row.category(), row.jobCount(),
                        row.percentageOfJobs(), row.rank(), rising.get(row.skillId()), onResume.contains(row.skillId())))
                        .toList(),
                covered.stream().sorted(Comparator.comparing(SkillResponse::name)).toList(),
                roadmap,
                progress(covered.size(), roadmap),
                false,
                note(resume, market));
    }

    /** Records progress on one roadmap skill of the caller's goal. */
    @Transactional
    public RoadmapSkillProgress setProgress(UUID goalId, Long skillId, SkillProgressStatus status) {
        CareerGoal goal = goalService.requireOwn(goalId);
        boolean onRoadmap = goal.getTargetSkills().stream().anyMatch(skill -> skill.getId().equals(skillId))
                || marketSkills(goal.getTargetCategory()).stream().anyMatch(row -> row.skillId().equals(skillId));
        if (!onRoadmap) {
            throw new InvalidRequestException("Skill " + skillId + " is not on this goal's roadmap");
        }
        OffsetDateTime now = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        CareerGoalSkillProgress saved = progressRepository.findById(new CareerGoalSkillProgress.Key(goalId, skillId))
                .map(existing -> {
                    existing.change(status, now);
                    return existing;
                })
                .orElseGet(() -> progressRepository.save(new CareerGoalSkillProgress(goalId, skillId, status, now)));
        return new RoadmapSkillProgress(goalId, skillId, saved.getStatus(), saved.getUpdatedAt());
    }

    public record RoadmapSkillProgress(UUID goalId, Long skillId, SkillProgressStatus status, OffsetDateTime updatedAt) {
    }

    // ------------------------------------------------------------------ calculation

    record Candidate(SkillResponse skill, Source source, Double percentageOfJobs, Integer demandRank) {
    }

    /** The V6.4 focus-area order: rising first (biggest rise first), then demand rank; chosen skills last. */
    static List<RoadmapSkill> prioritise(Map<Long, Candidate> candidates, Map<Long, Double> rising,
                                         Map<Long, SkillProgressStatus> progress, String category) {
        List<FocusArea> ordered = candidates.values().stream()
                .map(candidate -> new FocusArea(candidate.skill().id(), candidate.skill().name(),
                        candidate.percentageOfJobs() == null ? 0.0 : candidate.percentageOfJobs(),
                        candidate.demandRank() == null ? Integer.MAX_VALUE : candidate.demandRank(),
                        rising.get(candidate.skill().id())))
                .sorted(CareerInsightsService.FOCUS_ORDER)
                .toList();
        List<RoadmapSkill> roadmap = new ArrayList<>(ordered.size());
        for (int index = 0; index < ordered.size(); index++) {
            FocusArea area = ordered.get(index);
            Candidate candidate = candidates.get(area.skillId());
            roadmap.add(new RoadmapSkill(index + 1, area.skillId(), area.skill(), candidate.skill().category(),
                    candidate.source(), candidate.percentageOfJobs(), candidate.demandRank(),
                    area.changeInPercentagePoints(), reason(candidate, area.changeInPercentagePoints(), category),
                    progress.getOrDefault(area.skillId(), SkillProgressStatus.NOT_STARTED)));
        }
        return roadmap;
    }

    static String reason(Candidate candidate, Double rise, String category) {
        String base = candidate.source() == Source.MARKET_DEMAND
                ? "In %.1f%% of %s postings (rank %d)".formatted(candidate.percentageOfJobs(), category, candidate.demandRank())
                : "You added this skill to the goal";
        return rise == null ? base : base + "; rising by %.1f percentage points in recent postings".formatted(rise);
    }

    static Progress progress(int onResume, List<RoadmapSkill> roadmap) {
        Map<SkillProgressStatus, Long> counts = roadmap.stream()
                .collect(Collectors.groupingBy(RoadmapSkill::status, Collectors.counting()));
        int completed = counts.getOrDefault(SkillProgressStatus.COMPLETED, 0L).intValue();
        int inProgress = counts.getOrDefault(SkillProgressStatus.IN_PROGRESS, 0L).intValue();
        int notStarted = counts.getOrDefault(SkillProgressStatus.NOT_STARTED, 0L).intValue();
        int total = onResume + roadmap.size();
        Double percent = total == 0 ? null : Math.round((onResume + completed) * 1000.0 / total) / 10.0;
        return new Progress(total, onResume, completed, inProgress, notStarted, percent);
    }

    private static String note(Resume resume, List<EntitySkillResponse> market) {
        if (market.isEmpty()) {
            return "This category has no postings with skills right now, so only the skills you chose are listed. "
                    + NO_STAGES_NOTE;
        }
        return resume == null
                ? "No processed resume was found, so every skill counts as still to develop. " + NO_STAGES_NOTE
                : NO_STAGES_NOTE;
    }

    /** The category's top skills; none when it has no postings left (without the analytics call's 404). */
    private List<EntitySkillResponse> marketSkills(String category) {
        return analyticsRepository.countJobsInCategory(category) == 0
                ? List.of()
                : categoryAnalyticsService.skillsForCategory(category, MARKET_SKILLS);
    }

    /** The default resume if it is processed, otherwise the newest processed one. */
    private Resume currentResume() {
        List<Resume> resumes = resumeRepository.findByUserIdOrderByUploadedAtDesc(currentUser.requireId()).stream()
                .filter(Resume::isCompleted)
                .toList();
        return resumes.stream().filter(Resume::isDefaultResume).findFirst()
                .orElse(resumes.isEmpty() ? null : resumes.get(0));
    }

    private List<SkillResponse> sortedSkills(Set<Skill> skills) {
        return skills.stream().map(jobMapper::toSkill).sorted(Comparator.comparing(SkillResponse::name)).toList();
    }
}
