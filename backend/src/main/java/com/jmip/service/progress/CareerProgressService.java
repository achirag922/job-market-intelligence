package com.jmip.service.progress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmip.dto.career.RoadmapResponse;
import com.jmip.dto.portfolio.PortfolioDtos;
import com.jmip.dto.progress.CareerProgressDtos.Achievement;
import com.jmip.dto.progress.CareerProgressDtos.ApplicationProgress;
import com.jmip.dto.progress.CareerProgressDtos.CareerProgress;
import com.jmip.dto.progress.CareerProgressDtos.InterviewProgress;
import com.jmip.dto.progress.CareerProgressDtos.LearningProgress;
import com.jmip.dto.progress.CareerProgressDtos.Progress;
import com.jmip.dto.progress.CareerProgressDtos.Readiness;
import com.jmip.dto.progress.CareerProgressDtos.Streak;
import com.jmip.dto.progress.CareerProgressDtos.TargetRole;
import com.jmip.dto.resume.MatchPreferences;
import com.jmip.dto.resume.ResumeResponse;
import com.jmip.entity.ApplicationStatus;
import com.jmip.entity.CareerGoal;
import com.jmip.entity.CareerGoalStatus;
import com.jmip.entity.SavedJob;
import com.jmip.entity.SkillProgressStatus;
import com.jmip.repository.ApplicationEventRepository;
import com.jmip.repository.ApplicationEventRepository.Event;
import com.jmip.repository.CareerGoalRepository;
import com.jmip.repository.InterviewRepository;
import com.jmip.repository.InterviewRepository.SessionRow;
import com.jmip.repository.LearningRepository;
import com.jmip.repository.LearningRepository.ItemRow;
import com.jmip.repository.MatchPreferencesRepository;
import com.jmip.repository.PortfolioRepository;
import com.jmip.repository.SavedJobRepository;
import com.jmip.service.auth.CurrentUser;
import com.jmip.service.career.RoadmapService;
import com.jmip.service.resume.ResumeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * V9.13: career progress for the signed-in user: the readiness score, progress towards the target
 * role, one-time achievements and weekly streaks. Everything is read from the existing records
 * (match preferences, resumes, the career goal and its V7.4 roadmap, the V9.5 learning plan, V9.6
 * interviews, V8.5 application history, the V9.7 portfolio) and computed here; nothing new is stored.
 */
@Service
public class CareerProgressService {

    private static final Logger log = LoggerFactory.getLogger(CareerProgressService.class);

    static final int LEARNING_MILESTONE = 5;
    static final double STRONG_INTERVIEW = 4.0;

    private final CurrentUser currentUser;
    private final MatchPreferencesRepository matchPreferences;
    private final CareerGoalRepository goals;
    private final RoadmapService roadmapService;
    private final ResumeService resumeService;
    private final LearningRepository learning;
    private final InterviewRepository interviews;
    private final SavedJobRepository savedJobs;
    private final ApplicationEventRepository applicationEvents;
    private final PortfolioRepository portfolios;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CareerProgressService(CurrentUser currentUser, MatchPreferencesRepository matchPreferences,
                                 CareerGoalRepository goals, RoadmapService roadmapService, ResumeService resumeService,
                                 LearningRepository learning, InterviewRepository interviews, SavedJobRepository savedJobs,
                                 ApplicationEventRepository applicationEvents, PortfolioRepository portfolios,
                                 ObjectMapper objectMapper, Clock clock) {
        this.currentUser = currentUser;
        this.matchPreferences = matchPreferences;
        this.goals = goals;
        this.roadmapService = roadmapService;
        this.resumeService = resumeService;
        this.learning = learning;
        this.interviews = interviews;
        this.savedJobs = savedJobs;
        this.applicationEvents = applicationEvents;
        this.portfolios = portfolios;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CareerProgress progress() {
        UUID owner = currentUser.requireId();
        OffsetDateTime now = OffsetDateTime.now(clock);
        LocalDate today = day(now);

        MatchPreferences prefs = matchPreferences.find(owner);
        boolean experienceAndSkills = prefs.yearsExperience() != null && !prefs.preferredSkills().isEmpty();
        boolean jobPreferences = notBlank(prefs.preferredLocation()) || notBlank(prefs.workMode()) || prefs.minSalary() != null
                || !prefs.preferredCategories().isEmpty();

        List<CareerGoal> allGoals = goals.findByUserIdOrderByUpdatedAtDesc(owner);
        Optional<CareerGoal> active = allGoals.stream().filter(g -> g.getStatus() == CareerGoalStatus.ACTIVE).findFirst();
        RoadmapResponse roadmap = active.map(this::roadmapOf).orElse(null);
        Double roadmapPercent = roadmap == null || roadmap.progress().totalSkills() == 0 ? null : roadmap.progress().percentComplete();

        List<ResumeResponse> resumes = resumeService.list().stream().filter(r -> "COMPLETED".equals(r.status())).toList();
        UUID current = resumeService.currentProcessedResumeId().orElse(null);
        int resumeSkills = resumes.stream().filter(r -> r.id().equals(current)).findFirst().map(r -> r.skills().size()).orElse(0);

        List<ItemRow> items = learning.items(owner);
        List<ItemRow> completedItems = items.stream().filter(i -> i.completedAt() != null)
                .sorted(Comparator.comparing(ItemRow::completedAt)).toList();
        List<SessionRow> practices = interviews.list(owner).stream()
                .filter(s -> "COMPLETED".equals(s.status()) && s.completedAt() != null)
                .sorted(Comparator.comparing(SessionRow::completedAt)).toList();
        List<Double> practiceScores = practices.stream().map(SessionRow::averageScore).filter(Objects::nonNull)
                .map(BigDecimal::doubleValue).toList();
        Double averageScore = practiceScores.isEmpty() ? null
                : round(practiceScores.stream().mapToDouble(Double::doubleValue).average().orElse(0));

        List<SavedJob> saved = savedJobs.findByUserIdOrderByUpdatedAtDesc(owner);
        List<Event> events = applicationEvents.findByUser(owner);
        OffsetDateTime monthAgo = now.minusDays(30);
        int savedLast30 = (int) events.stream().filter(e -> e.status() == ApplicationStatus.SAVED && e.changedAt().isAfter(monthAgo)).count();
        int appliedLast30 = (int) events.stream().filter(e -> e.status() == ApplicationStatus.APPLIED && e.changedAt().isAfter(monthAgo)).count();

        Optional<PortfolioRepository.Row> portfolio = portfolios.find(owner);
        int portfolioSections = portfolio.map(this::filledSections).orElse(0);
        boolean published = portfolio.map(p -> "PUBLIC".equals(p.visibility())).orElse(false);

        Readiness readiness = CareerProgressRules.readiness(new CareerProgressRules.Inputs(experienceAndSkills, jobPreferences,
                active.isPresent(), current != null, resumeSkills, roadmapPercent, items.size(), completedItems.size(),
                practices.size(), averageScore, savedLast30, appliedLast30, portfolio.isPresent(), published, portfolioSections));

        Progress progress = new Progress(target(active.orElse(null), roadmap, roadmapPercent),
                new LearningProgress(items.size(), completedItems.size(),
                        (int) items.stream().filter(i -> "IN_PROGRESS".equals(i.status())).count(),
                        items.isEmpty() ? null : round(items.stream().mapToInt(ItemRow::progress).average().orElse(0))),
                new InterviewProgress(practices.size(), averageScore, practiceScores.isEmpty() ? null : practiceScores.get(practiceScores.size() - 1)),
                new ApplicationProgress(saved.size(), (int) saved.stream().filter(s -> s.getStatus() != ApplicationStatus.SAVED).count(),
                        (int) saved.stream().filter(s -> s.getStatus() == ApplicationStatus.INTERVIEW).count(),
                        (int) saved.stream().filter(s -> s.getStatus() == ApplicationStatus.OFFER).count()));

        List<Achievement> achievements = new ArrayList<>();
        achievements.add(achievement("career-goal", "Career goal set", "You chose a role to work towards.", "Career",
                allGoals.stream().map(CareerGoal::getCreatedAt).filter(Objects::nonNull).min(Comparator.naturalOrder()).map(CareerProgressService::day),
                !allGoals.isEmpty(), "Set a career goal"));
        int profileParts = (experienceAndSkills ? 1 : 0) + (jobPreferences ? 1 : 0) + (active.isPresent() ? 1 : 0);
        achievements.add(achievement("profile", "Profile completed", "Experience, skills, job preferences and an active goal are all set.",
                "Career", Optional.empty(), profileParts == 3, profileParts + " of 3 parts done"));
        achievements.add(achievement("resume", "Resume completed", "A resume of yours was processed and its skills recognised.", "Resume",
                resumes.stream().map(r -> r.processedAt() != null ? r.processedAt() : r.uploadedAt()).filter(Objects::nonNull)
                        .min(Comparator.naturalOrder()).map(CareerProgressService::day), !resumes.isEmpty(), "Upload or build a resume"));
        achievements.add(achievement("first-saved-job", "First job saved", "You saved a job to follow up on.", "Job search",
                firstEvent(events, ApplicationStatus.SAVED).or(() -> saved.stream().map(SavedJob::getSavedAt).min(Comparator.naturalOrder()).map(CareerProgressService::day)),
                !saved.isEmpty() || firstEvent(events, ApplicationStatus.SAVED).isPresent(), "Save a job"));
        Optional<LocalDate> firstApplication = firstEvent(events, ApplicationStatus.APPLIED);
        achievements.add(achievement("first-application", "First application", "You applied to a job.", "Job search",
                firstApplication, firstApplication.isPresent(), "Mark a saved job as applied"));
        Optional<LocalDate> firstInterview = firstEvent(events, ApplicationStatus.INTERVIEW);
        achievements.add(achievement("first-interview", "First interview stage", "An application reached the interview stage.", "Job search",
                firstInterview, firstInterview.isPresent(), "Keep applying; interviews follow"));
        Optional<LocalDate> firstOffer = firstEvent(events, ApplicationStatus.OFFER);
        achievements.add(achievement("first-offer", "First offer", "An application turned into an offer.", "Job search",
                firstOffer, firstOffer.isPresent(), "Your first offer will show here"));
        achievements.add(achievement("first-practice", "First interview practice", "You completed a practice interview.", "Interviews",
                practices.stream().findFirst().map(s -> day(s.completedAt())), !practices.isEmpty(), "Complete a practice interview"));
        Optional<SessionRow> strong = practices.stream().filter(s -> s.averageScore() != null && s.averageScore().doubleValue() >= STRONG_INTERVIEW).findFirst();
        achievements.add(achievement("strong-practice", "Strong interview practice", "A practice interview averaged 4 out of 5 or more.", "Interviews",
                strong.map(s -> day(s.completedAt())), strong.isPresent(),
                practiceScores.isEmpty() ? "Score 4/5 or more in a practice interview"
                        : "Best so far " + practiceScores.stream().max(Double::compare).map(CareerProgressService::format).orElse("") + "/5"));
        achievements.add(achievement("first-skill", "Skill completed", "You completed an item in your learning plan.", "Learning",
                completedItems.stream().findFirst().map(i -> day(i.completedAt())), !completedItems.isEmpty(), "Complete a learning item"));
        achievements.add(achievement("learning-five", "Learning milestone", "Five learning items completed.", "Learning",
                completedItems.size() >= LEARNING_MILESTONE ? Optional.of(day(completedItems.get(LEARNING_MILESTONE - 1).completedAt())) : Optional.empty(),
                completedItems.size() >= LEARNING_MILESTONE, Math.min(completedItems.size(), LEARNING_MILESTONE) + " of " + LEARNING_MILESTONE + " completed"));
        achievements.add(achievement("roadmap-halfway", "Halfway to your target role", "Half of your roadmap's skills are covered.", "Career",
                Optional.empty(), roadmapPercent != null && roadmapPercent >= 50,
                roadmapPercent == null ? "Set a career goal" : format(roadmapPercent) + "% covered"));
        achievements.add(achievement("portfolio-published", "Portfolio published", "Your professional profile is public.", "Portfolio",
                portfolio.map(PortfolioRepository.Row::publishedAt).filter(Objects::nonNull).map(CareerProgressService::day),
                published, portfolio.isPresent() ? "Publish your portfolio" : "Create and publish your portfolio"));

        List<Achievement> next = achievements.stream().filter(a -> !a.achieved()).limit(3).toList();

        List<Streak> streaks = List.of(
                CareerProgressRules.weeklyStreak("learning", "Learning", Stream.concat(
                        items.stream().map(ItemRow::startedAt), items.stream().map(ItemRow::completedAt))
                        .filter(Objects::nonNull).map(CareerProgressService::day).toList(), today),
                CareerProgressRules.weeklyStreak("interviews", "Interview practice",
                        practices.stream().map(s -> day(s.completedAt())).toList(), today),
                CareerProgressRules.weeklyStreak("jobSearch", "Job search", events.stream()
                        .filter(e -> e.status() == ApplicationStatus.SAVED || e.status() == ApplicationStatus.APPLIED)
                        .map(e -> day(e.changedAt())).toList(), today));

        return new CareerProgress(readiness, progress, achievements, next, streaks, List.of(
                "The score adds up the seven components shown. Each is capped, so repeating an action beyond what helps earns nothing.",
                "Achievements are earned once. Streaks count weeks with at least one real step and are not part of the score.",
                "Dates appear where JMIP recorded when something happened."));
    }

    private RoadmapResponse roadmapOf(CareerGoal goal) {
        try {
            return roadmapService.roadmap(goal.getId(), null);
        } catch (RuntimeException unavailable) {
            log.debug("Roadmap unavailable for career progress: {}", unavailable.getClass().getSimpleName());
            return null;
        }
    }

    private static TargetRole target(CareerGoal goal, RoadmapResponse roadmap, Double percent) {
        if (goal == null) {
            return null;
        }
        if (roadmap == null) {
            return new TargetRole(goal.getTargetRole(), goal.getTargetCategory(), null, 0, 0, 0, 0, List.of());
        }
        RoadmapResponse.Progress p = roadmap.progress();
        List<String> nextSkills = roadmap.roadmap().stream().filter(s -> s.status() != SkillProgressStatus.COMPLETED)
                .sorted(Comparator.comparingInt(RoadmapResponse.RoadmapSkill::priority)).limit(3)
                .map(RoadmapResponse.RoadmapSkill::skill).toList();
        return new TargetRole(goal.getTargetRole(), goal.getTargetCategory(), percent == null ? null : round(percent),
                p.totalSkills(), p.onResume(), p.completed(), p.inProgress(), nextSkills);
    }

    private int filledSections(PortfolioRepository.Row row) {
        try {
            PortfolioDtos.Content c = objectMapper.readValue(row.content(), PortfolioDtos.Content.class);
            return (c.about() != null ? 1 : 0) + Stream.of(c.skills(), c.experience(), c.education(), c.projects(),
                    c.certifications(), c.achievements(), c.links()).mapToInt(list -> list.isEmpty() ? 0 : 1).sum();
        } catch (java.io.IOException unreadable) {
            return 0;
        }
    }

    private static Achievement achievement(String key, String title, String description, String category,
                                           Optional<LocalDate> on, boolean achieved, String toDo) {
        return new Achievement(key, title, description, category, achieved, achieved ? on.orElse(null) : null,
                achieved ? null : toDo);
    }

    private static Optional<LocalDate> firstEvent(List<Event> events, ApplicationStatus status) {
        return events.stream().filter(e -> e.status() == status).map(Event::changedAt).min(Comparator.naturalOrder())
                .map(CareerProgressService::day);
    }

    private static LocalDate day(OffsetDateTime at) {
        return at.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, value == Math.rint(value) ? "%.0f" : "%.1f", value);
    }
}
