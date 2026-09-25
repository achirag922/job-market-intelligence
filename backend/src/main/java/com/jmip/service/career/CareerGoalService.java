package com.jmip.service.career;

import com.jmip.common.exception.InvalidRequestException;
import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.dto.SkillResponse;
import com.jmip.dto.career.CareerGoalRequest;
import com.jmip.dto.career.CareerGoalResponse;
import com.jmip.entity.CareerGoal;
import com.jmip.entity.CareerGoalStatus;
import com.jmip.entity.Skill;
import com.jmip.mapper.JobMapper;
import com.jmip.repository.AnalyticsRepository;
import com.jmip.repository.CareerGoalRepository;
import com.jmip.repository.SkillRepository;
import com.jmip.service.auth.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * V7.4: career goals of the signed-in account. Every operation is scoped to the account taken
 * from the session; another account's goal answers exactly like a missing one (404).
 */
@Service
public class CareerGoalService {

    /** Enough for real use, and a bound on what one account can store. */
    static final int MAX_GOALS_PER_ACCOUNT = 20;

    private static final Logger log = LoggerFactory.getLogger(CareerGoalService.class);

    private final CareerGoalRepository repository;
    private final SkillRepository skillRepository;
    private final AnalyticsRepository analyticsRepository;
    private final JobMapper jobMapper;
    private final CurrentUser currentUser;
    private final Clock clock;

    public CareerGoalService(CareerGoalRepository repository, SkillRepository skillRepository,
                             AnalyticsRepository analyticsRepository, JobMapper jobMapper,
                             CurrentUser currentUser, Clock clock) {
        this.repository = repository;
        this.skillRepository = skillRepository;
        this.analyticsRepository = analyticsRepository;
        this.jobMapper = jobMapper;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Transactional
    public CareerGoalResponse create(CareerGoalRequest request) {
        UUID ownerId = currentUser.requireId();
        if (repository.countByUserId(ownerId) >= MAX_GOALS_PER_ACCOUNT) {
            throw new InvalidRequestException(
                    "You can have at most " + MAX_GOALS_PER_ACCOUNT + " career goals; delete one to add another");
        }
        requireKnownCategory(request.targetCategory());
        CareerGoal goal = repository.save(new CareerGoal(UUID.randomUUID(), ownerId, request.details(),
                resolveSkills(request.targetSkills()), now()));
        log.info("Career goal {} created", goal.getId());
        return toResponse(goal);
    }

    @Transactional(readOnly = true)
    public List<CareerGoalResponse> list(CareerGoalStatus status) {
        UUID ownerId = currentUser.requireId();
        List<CareerGoal> goals = status == null
                ? repository.findByUserIdOrderByUpdatedAtDesc(ownerId)
                : repository.findByUserIdAndStatusOrderByUpdatedAtDesc(ownerId, status);
        return goals.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public CareerGoalResponse get(UUID id) {
        return toResponse(requireOwn(id));
    }

    @Transactional
    public CareerGoalResponse update(UUID id, CareerGoalRequest request) {
        CareerGoal goal = requireOwn(id);
        if (!goal.getTargetCategory().equals(request.targetCategory())) {
            requireKnownCategory(request.targetCategory());
        }
        goal.update(request.details(), resolveSkills(request.targetSkills()), now());
        return toResponse(goal);
    }

    @Transactional
    public CareerGoalResponse changeStatus(UUID id, CareerGoalStatus status) {
        CareerGoal goal = requireOwn(id);
        goal.changeStatus(status, now());
        return toResponse(goal);
    }

    /** Removes the goal, its chosen skills and its roadmap progress (cascaded by the schema). */
    @Transactional
    public void delete(UUID id) {
        repository.delete(requireOwn(id));
        log.info("Career goal {} deleted", id);
    }

    /** The caller's goal, or 404 — for the roadmap too. */
    CareerGoal requireOwn(UUID id) {
        return repository.findByIdAndUserId(id, currentUser.requireId())
                .orElseThrow(() -> ResourceNotFoundException.of("Career goal", id));
    }

    CareerGoalResponse toResponse(CareerGoal goal) {
        return new CareerGoalResponse(goal.getId(), goal.getTargetRole(), goal.getTargetCategory(),
                goal.getTargetLocation(), goal.getTargetExperience(), sorted(goal.getTargetSkills()),
                goal.getStatus(), goal.getCreatedAt(), goal.getUpdatedAt());
    }

    /** The category must have postings, or there is no market demand to build a roadmap from. */
    private void requireKnownCategory(String category) {
        // A count, not the analytics call: that one throws inside its own transaction, which
        // would mark this one rollback-only even when caught.
        if (analyticsRepository.countJobsInCategory(category) == 0) {
            throw new InvalidRequestException("targetCategory must be an existing job category; '" + category
                    + "' has no postings");
        }
    }

    /** Each named skill must already exist; the name is matched exactly, ignoring case. */
    private Set<Skill> resolveSkills(List<String> names) {
        Set<Skill> skills = new LinkedHashSet<>();
        for (String name : names) {
            skills.add(skillRepository.findFirstByNameIgnoreCase(name)
                    .orElseThrow(() -> new InvalidRequestException("Unknown skill '" + name
                            + "'; choose a skill that appears in job postings")));
        }
        return skills;
    }

    private List<SkillResponse> sorted(Set<Skill> skills) {
        return skills.stream().map(jobMapper::toSkill).sorted(Comparator.comparing(SkillResponse::name)).toList();
    }

    private OffsetDateTime now() {
        // PostgreSQL keeps microseconds; a response then shows exactly what was stored.
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
