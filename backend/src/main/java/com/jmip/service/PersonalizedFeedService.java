package com.jmip.service;

import com.jmip.dto.PersonalizedFeedResponse;
import com.jmip.dto.PersonalizedFeedResponse.Context;
import com.jmip.dto.PersonalizedFeedResponse.Item;
import com.jmip.dto.PersonalizedFeedResponse.Reason;
import com.jmip.dto.SkillResponse;
import com.jmip.dto.resume.MatchBreakdown;
import com.jmip.dto.resume.MatchBreakdown.Dimension;
import com.jmip.dto.resume.MatchBreakdown.Status;
import com.jmip.dto.resume.MatchPreferences;
import com.jmip.dto.resume.ResumeMatchResponse;
import com.jmip.entity.ApplicationStatus;
import com.jmip.entity.CareerGoal;
import com.jmip.entity.CareerGoalStatus;
import com.jmip.entity.Job;
import com.jmip.entity.Location;
import com.jmip.entity.SavedJob;
import com.jmip.entity.Skill;
import com.jmip.mapper.JobMapper;
import com.jmip.repository.CareerGoalRepository;
import com.jmip.repository.JobRepository;
import com.jmip.repository.MatchPreferencesRepository;
import com.jmip.repository.SavedJobRepository;
import com.jmip.service.auth.CurrentUser;
import com.jmip.service.resume.ResumeMatchService;
import com.jmip.service.resume.ResumeService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * V9.2: the personalized job feed. The score is the match ({@link ResumeMatchService}), which since
 * V9.3 already covers the career goal and preferred roles and skills; on top of it two listed signals
 * that are not about fit order the feed: freshness, and the categories the user applied to. Jobs already applied to,
 * excluded by the user, or no longer active are left out; saved jobs stay, marked. Everything is
 * the signed-in user's own: the account comes from the session only.
 */
@Service
@Transactional(readOnly = true)
public class PersonalizedFeedService {

    static final int CANDIDATES_PER_SOURCE = 100;
    static final int FRESH_POINTS = 5;
    static final int HISTORY_POINTS = 4;
    static final int FRESH_DAYS = 7;
    static final double STRONG_SKILL_MATCH = 70.0;

    private final JobRepository jobs;
    private final JobMapper jobMapper;
    private final ResumeService resumeService;
    private final ResumeMatchService matchService;
    private final MatchPreferencesRepository preferences;
    private final CareerGoalRepository goals;
    private final SavedJobRepository savedJobs;
    private final CurrentUser currentUser;
    private final Clock clock;

    public PersonalizedFeedService(JobRepository jobs, JobMapper jobMapper, ResumeService resumeService,
                                   ResumeMatchService matchService, MatchPreferencesRepository preferences,
                                   CareerGoalRepository goals, SavedJobRepository savedJobs, CurrentUser currentUser,
                                   Clock clock) {
        this.jobs = jobs;
        this.jobMapper = jobMapper;
        this.resumeService = resumeService;
        this.matchService = matchService;
        this.preferences = preferences;
        this.goals = goals;
        this.savedJobs = savedJobs;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    public PersonalizedFeedResponse feed(int limit) {
        UUID owner = currentUser.requireId();
        MatchPreferences prefs = preferences.find(owner);
        Optional<CareerGoal> goal = goals.findByUserIdAndStatusOrderByUpdatedAtDesc(owner, CareerGoalStatus.ACTIVE)
                .stream().findFirst();
        List<SavedJob> tracked = savedJobs.findByUserIdOrderByUpdatedAtDesc(owner);
        Set<Long> saved = tracked.stream().filter(s -> s.getStatus() == ApplicationStatus.SAVED)
                .map(s -> s.getJob().getId()).collect(Collectors.toSet());
        Set<Long> applied = tracked.stream().filter(s -> s.getStatus() != ApplicationStatus.SAVED)
                .map(s -> s.getJob().getId()).collect(Collectors.toSet());
        Set<String> appliedCategories = tracked.stream().filter(s -> s.getStatus() != ApplicationStatus.SAVED)
                .map(s -> lower(s.getJob().getJobCategory())).filter(c -> c != null).collect(Collectors.toSet());
        Optional<UUID> resumeId = resumeService.currentProcessedResumeId();

        // Candidates: the best skill matches, the newest in the roles the user wants, and the newest overall.
        Set<Long> ids = new LinkedHashSet<>();
        resumeId.ifPresent(id -> {
            Set<Long> skills = resumeService.requireCompletedResume(id).getSkills().stream().map(Skill::getId)
                    .collect(Collectors.toSet());
            if (!skills.isEmpty()) {
                ids.addAll(jobs.findRecommendationJobIds(skills, PageRequest.of(0, CANDIDATES_PER_SOURCE)));
            }
        });
        Set<String> roles = new LinkedHashSet<>(prefs.preferredCategories().stream().map(PersonalizedFeedService::lower).toList());
        goal.ifPresent(g -> roles.add(lower(g.getTargetCategory())));
        if (!roles.isEmpty()) {
            ids.addAll(jobs.findActiveIdsInCategories(roles, PageRequest.of(0, CANDIDATES_PER_SOURCE)));
        }
        ids.addAll(jobs.findNewestActiveIds(PageRequest.of(0, CANDIDATES_PER_SOURCE)));

        Map<Long, Job> byId = jobs.findRecommendationDetailsByIdIn(ids).stream()
                .collect(Collectors.toMap(Job::getId, Function.identity()));
        int excludedApplied = 0;
        int excludedByPreference = 0;
        List<Job> candidates = new ArrayList<>();
        for (Long id : ids) {
            Job job = byId.get(id);
            if (job == null || !job.isActive()) {
                continue;
            }
            if (applied.contains(id)) {
                excludedApplied++;
            } else if (excluded(job, prefs)) {
                excludedByPreference++;
            } else {
                candidates.add(job);
            }
        }
        Map<Long, ResumeMatchResponse> matches = resumeId
                .map(id -> matchService.matchJobs(id, candidates.stream().map(Job::getId).toList()))
                .orElse(Map.of());

        OffsetDateTime freshSince = OffsetDateTime.now(clock).minusDays(FRESH_DAYS);
        List<Item> items = candidates.stream()
                .map(job -> item(job, matches.get(job.getId()), prefs, goal, appliedCategories, saved, freshSince))
                .sorted(Comparator.comparingDouble(Item::priority).reversed()
                        .thenComparing(item -> item.matchPercentage() == null ? -1 : item.matchPercentage(), Comparator.reverseOrder())
                        .thenComparing(item -> item.job().id(), Comparator.reverseOrder()))
                .limit(limit)
                .toList();

        String note = resumeId.isEmpty()
                ? "Upload a resume to add a skill match to every job; the feed uses your goal and preferences meanwhile."
                : null;
        return new PersonalizedFeedResponse(items, new Context(resumeId.isPresent(),
                goal.map(CareerGoal::getTargetRole).orElse(null), prefs.preferredCategories(), prefs.preferredSkills(),
                ids.size(), excludedApplied, excludedByPreference), note);
    }

    private Item item(Job job, ResumeMatchResponse match, MatchPreferences prefs, Optional<CareerGoal> goal,
                      Set<String> appliedCategories, Set<Long> saved, OffsetDateTime freshSince) {
        List<Reason> reasons = new ArrayList<>();
        MatchBreakdown breakdown = match == null ? null : match.breakdown();
        Double overall = breakdown == null ? null : breakdown.overallPercentage();
        double priority = overall == null ? 0 : overall;

        // What the V8.3 match found, in words.
        if (match != null && match.matchPercentage() != null) {
            if (match.matchPercentage() >= STRONG_SKILL_MATCH) {
                reasons.add(new Reason("Strong skill match (" + match.matchedSkillCount() + " of " + match.totalJobSkills()
                        + " skills)", "POSITIVE", null));
            } else if (match.matchedSkillCount() > 0) {
                reasons.add(new Reason("Partial skill match (" + match.matchedSkillCount() + " of " + match.totalJobSkills()
                        + " skills)", "INFO", null));
            }
            if (match.missingSkillCount() > 0) {
                reasons.add(new Reason("Missing " + match.missingSkillCount() + " required skill"
                        + (match.missingSkillCount() == 1 ? "" : "s"), "NEGATIVE", null));
            }
        }
        if (breakdown != null) {
            // V9.3: goal, role and preferred skills are part of the match itself now; their words come from it.
            dimension(reasons, breakdown.careerGoal(), breakdown.careerGoal() == null ? null
                    : "Matches your career goal: " + goal.map(CareerGoal::getTargetRole).orElse(""), "Different role from your career goal");
            if (breakdown.careerGoal() != null && breakdown.careerGoal().status() == Status.PARTIAL) {
                reasons.add(new Reason(breakdown.careerGoal().detail(), "POSITIVE", null));
            }
            dimension(reasons, breakdown.role(), breakdown.role() == null ? null : breakdown.role().detail(),
                    "Not one of your preferred roles");
            dimension(reasons, breakdown.preferredSkills(), breakdown.preferredSkills() == null ? null
                    : breakdown.preferredSkills().detail(), null);
            dimension(reasons, breakdown.location(), "Preferred location", "Outside your preferred location");
            dimension(reasons, breakdown.workMode(), label(prefs.workMode()) + " preference matched", "Work mode differs from your preference");
            dimension(reasons, breakdown.experience(), "Experience fits", "Experience asked is above yours");
            dimension(reasons, breakdown.salary(), "Salary meets your minimum", "Salary below your minimum");
        }

        // The signals that are not about fit, each with its points.
        String category = lower(job.getJobCategory());
        if (job.getFirstSeenAt() != null && job.getFirstSeenAt().isAfter(freshSince)) {
            priority += FRESH_POINTS;
            reasons.add(new Reason("New in the last " + FRESH_DAYS + " days", "POSITIVE", FRESH_POINTS));
        }
        if (category != null && appliedCategories.contains(category)) {
            priority += HISTORY_POINTS;
            reasons.add(new Reason("Similar to jobs you applied to", "POSITIVE", HISTORY_POINTS));
        }
        boolean isSaved = saved.contains(job.getId());
        if (isSaved) {
            reasons.add(new Reason("You saved this job", "INFO", null));
        }
        List<SkillResponse> skills = job.getSkills().stream().map(jobMapper::toSkill)
                .sorted(Comparator.comparing(SkillResponse::name)).toList();
        return new Item(jobMapper.toSummary(job, skills), overall, Math.round(priority * 10.0) / 10.0, isSaved, reasons,
                breakdown);
    }

    private static void dimension(List<Reason> reasons, Dimension dimension, String match, String noMatch) {
        if (dimension == null) {
            return;
        }
        if (dimension.status() == Status.MATCH && match != null) {
            reasons.add(new Reason(match, "POSITIVE", null));
        } else if (dimension.status() == Status.NO_MATCH && noMatch != null) {
            reasons.add(new Reason(noMatch, "NEGATIVE", null));
        }
    }

    /** Left out when the company, or any part of the location, is one the user excluded. */
    static boolean excluded(Job job, MatchPreferences prefs) {
        String company = job.getCompany() == null ? null : lower(job.getCompany().getName());
        if (company != null && prefs.excludedCompanies().stream().anyMatch(name -> company.equals(lower(name)))) {
            return true;
        }
        Location location = job.getLocation();
        if (location == null || prefs.excludedLocations().isEmpty()) {
            return false;
        }
        Set<String> parts = Stream.of(location.getCity(), location.getState(), location.getCountry())
                .map(PersonalizedFeedService::lower).filter(part -> part != null).collect(Collectors.toSet());
        return prefs.excludedLocations().stream().map(PersonalizedFeedService::lower).anyMatch(parts::contains);
    }

    private static String label(String workMode) {
        if (workMode == null) {
            return "Work mode";
        }
        return switch (workMode) {
            case "REMOTE" -> "Remote";
            case "HYBRID" -> "Hybrid";
            default -> "On-site";
        };
    }

    private static String lower(String value) {
        return value == null || value.isBlank() ? null : value.strip().toLowerCase(Locale.ROOT);
    }
}
