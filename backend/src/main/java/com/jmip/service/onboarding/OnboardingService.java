package com.jmip.service.onboarding;

import com.jmip.common.exception.InvalidRequestException;
import com.jmip.dto.onboarding.OnboardingDtos.PreferencesDraft;
import com.jmip.dto.onboarding.OnboardingDtos.PreferencesRequest;
import com.jmip.dto.onboarding.OnboardingDtos.ProfileDraft;
import com.jmip.dto.onboarding.OnboardingDtos.ProfileRequest;
import com.jmip.dto.onboarding.OnboardingDtos.Status;
import com.jmip.dto.onboarding.OnboardingDtos.Steps;
import com.jmip.dto.resume.MatchPreferences;
import com.jmip.entity.CareerGoalStatus;
import com.jmip.repository.CareerGoalRepository;
import com.jmip.repository.MatchPreferencesRepository;
import com.jmip.repository.OnboardingRepository;
import com.jmip.repository.OnboardingRepository.Row;
import com.jmip.service.auth.CurrentUser;
import com.jmip.service.resume.ResumeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * V9.12: first-time onboarding for the signed-in user. It owns only its progress: every answer is
 * stored where JMIP already keeps it, so the existing matching (V8.3/V9.3), personalized feed (V9.2),
 * roadmap (V7.4) and dashboard (V7.7) use it with no change. Experience, skills and job preferences go
 * to the match preferences; the resume is uploaded and parsed by the existing resume endpoints, and
 * the goal is created by the existing career-goal endpoint, so those two steps count as done when the
 * data exists, however it was added.
 */
@Service
public class OnboardingService {

    private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);

    private final OnboardingRepository repository;
    private final MatchPreferencesRepository preferences;
    private final ResumeService resumeService;
    private final CareerGoalRepository goals;
    private final CurrentUser currentUser;
    private final Clock clock;

    public OnboardingService(OnboardingRepository repository, MatchPreferencesRepository preferences,
                             ResumeService resumeService, CareerGoalRepository goals, CurrentUser currentUser,
                             Clock clock) {
        this.repository = repository;
        this.preferences = preferences;
        this.resumeService = resumeService;
        this.goals = goals;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Status status() {
        return status(currentUser.requireId());
    }

    /** Experience and skills replace those in the match preferences; everything else there is kept. */
    @Transactional
    public Status saveProfile(ProfileRequest request) {
        UUID owner = currentUser.requireId();
        MatchPreferences current = preferences.find(owner);
        preferences.save(owner, new MatchPreferences(request.yearsExperience(), current.preferredLocation(),
                current.workMode(), current.minSalary(), current.salaryCurrency(), current.preferredCategories(),
                request.skills(), current.excludedCompanies(), current.excludedLocations()));
        repository.saveProfile(owner, request.targetRole().strip(), now());
        return status(owner);
    }

    /** Location, work mode, salary and categories replace those in the match preferences; the rest is kept. */
    @Transactional
    public Status savePreferences(PreferencesRequest request) {
        UUID owner = currentUser.requireId();
        boolean anything = notBlank(request.preferredLocation()) || notBlank(request.workMode())
                || request.minSalary() != null
                || (request.preferredCategories() != null && !request.preferredCategories().isEmpty());
        if (!anything) {
            throw new InvalidRequestException("Choose at least one preference, or skip this step");
        }
        if (request.minSalary() != null && !notBlank(request.salaryCurrency())) {
            throw new InvalidRequestException("Choose the currency of your minimum salary");
        }
        MatchPreferences current = preferences.find(owner);
        preferences.save(owner, new MatchPreferences(current.yearsExperience(), request.preferredLocation(),
                request.workMode(), request.minSalary(), request.minSalary() == null ? null : request.salaryCurrency(),
                request.preferredCategories(), current.preferredSkills(), current.excludedCompanies(),
                current.excludedLocations()));
        repository.savePreferences(owner, now());
        return status(owner);
    }

    /** Leaves onboarding for now; it can be finished later. A completed onboarding stays completed. */
    @Transactional
    public Status skip() {
        UUID owner = currentUser.requireId();
        repository.setStatus(owner, "SKIPPED", now());
        log.info("Onboarding skipped");
        return status(owner);
    }

    @Transactional
    public Status complete() {
        UUID owner = currentUser.requireId();
        repository.setStatus(owner, "COMPLETED", now());
        log.info("Onboarding completed");
        return status(owner);
    }

    private Status status(UUID owner) {
        Row row = repository.find(owner).orElse(null);
        MatchPreferences saved = preferences.find(owner);
        Steps steps = new Steps(
                row != null && row.profileCompletedAt() != null,
                resumeService.currentProcessedResumeId().isPresent(),
                row != null && row.preferencesCompletedAt() != null,
                !goals.findByUserIdAndStatusOrderByUpdatedAtDesc(owner, CareerGoalStatus.ACTIVE).isEmpty());
        List<Boolean> done = List.of(steps.profile(), steps.resume(), steps.preferences(), steps.careerGoal());
        String[] names = {"PROFILE", "RESUME", "PREFERENCES", "CAREER_GOAL"};
        String next = "DONE";
        for (int i = 0; i < names.length; i++) {
            if (!done.get(i)) {
                next = names[i];
                break;
            }
        }
        return new Status(row == null ? "PENDING" : row.status(), steps, (int) done.stream().filter(d -> d).count(),
                names.length, next,
                new ProfileDraft(row == null ? null : row.targetRole(), saved.yearsExperience(), saved.preferredSkills()),
                new PreferencesDraft(saved.preferredLocation(), saved.workMode(), saved.minSalary(), saved.salaryCurrency(),
                        saved.preferredCategories()),
                row == null ? null : row.completedAt(), row == null ? null : row.skippedAt());
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
