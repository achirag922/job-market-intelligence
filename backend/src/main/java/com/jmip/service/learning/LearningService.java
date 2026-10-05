package com.jmip.service.learning;

import com.jmip.common.exception.InvalidRequestException;
import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.dto.career.RoadmapResponse;
import com.jmip.dto.learning.LearningDtos.Impact;
import com.jmip.dto.learning.LearningDtos.Item;
import com.jmip.dto.learning.LearningDtos.ItemRequest;
import com.jmip.dto.learning.LearningDtos.ItemUpdate;
import com.jmip.dto.learning.LearningDtos.Plan;
import com.jmip.dto.learning.LearningDtos.Priority;
import com.jmip.dto.learning.LearningDtos.Progress;
import com.jmip.dto.learning.LearningDtos.Resource;
import com.jmip.dto.learning.LearningDtos.ResourceRequest;
import com.jmip.dto.learning.LearningDtos.SkillDemand;
import com.jmip.dto.learning.LearningDtos.StatusRequest;
import com.jmip.entity.CareerGoal;
import com.jmip.entity.CareerGoalStatus;
import com.jmip.entity.SavedJob;
import com.jmip.entity.Skill;
import com.jmip.entity.SkillProgressStatus;
import com.jmip.repository.CareerGoalRepository;
import com.jmip.repository.LearningRepository;
import com.jmip.repository.LearningRepository.ItemRow;
import com.jmip.repository.SavedJobRepository;
import com.jmip.repository.SkillRepository;
import com.jmip.service.auth.CurrentUser;
import com.jmip.service.career.RoadmapService;
import com.jmip.service.resume.ResumeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * V9.5: the signed-in user's learning plan. Priority skills are the V7.4 roadmap's own ranking of
 * the active career goal (market demand and the goal's chosen skills, minus what the resume shows);
 * completing an item records that roadmap skill as completed through {@link RoadmapService}. The
 * resume is never changed: a learned skill counts in job matches once the user adds it there.
 */
@Service
@Transactional(readOnly = true)
public class LearningService {

    private static final Logger log = LoggerFactory.getLogger(LearningService.class);

    static final int MAX_ITEMS = 100;
    static final int MAX_RESOURCES_PER_ITEM = 20;

    private final LearningRepository repository;
    private final CareerGoalRepository goals;
    private final RoadmapService roadmapService;
    private final ResumeService resumeService;
    private final SkillRepository skills;
    private final SavedJobRepository savedJobs;
    private final CurrentUser currentUser;
    private final Clock clock;

    public LearningService(LearningRepository repository, CareerGoalRepository goals, RoadmapService roadmapService,
                           ResumeService resumeService, SkillRepository skills, SavedJobRepository savedJobs,
                           CurrentUser currentUser, Clock clock) {
        this.repository = repository;
        this.goals = goals;
        this.roadmapService = roadmapService;
        this.resumeService = resumeService;
        this.skills = skills;
        this.savedJobs = savedJobs;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    public Plan plan() {
        UUID owner = currentUser.requireId();
        Optional<CareerGoal> goal = activeGoal(owner);
        Optional<RoadmapResponse> roadmap = goal.map(g -> roadmapService.roadmap(g.getId(), null));
        List<Item> items = items(owner, roadmap);

        Map<Long, UUID> itemBySkill = new LinkedHashMap<>();
        items.stream().filter(item -> item.skillId() != null).forEach(item -> itemBySkill.putIfAbsent(item.skillId(), item.id()));
        List<Priority> priorities = roadmap.map(r -> r.roadmap().stream()
                .map(skill -> new Priority(skill.priority(), skill.skillId(), skill.skill(), skill.reason(),
                        skill.status() == null ? null : skill.status().name(), priorityFor(skill.priority()),
                        itemBySkill.get(skill.skillId())))
                .toList()).orElse(List.of());

        int notStarted = (int) items.stream().filter(i -> "NOT_STARTED".equals(i.status())).count();
        int inProgress = (int) items.stream().filter(i -> "IN_PROGRESS".equals(i.status())).count();
        List<Item> completed = items.stream().filter(i -> "COMPLETED".equals(i.status())).toList();
        Double average = items.isEmpty() ? null
                : Math.round(items.stream().mapToInt(Item::progress).average().orElse(0) * 10.0) / 10.0;
        Progress progress = new Progress(items.size(), notStarted, inProgress, completed.size(), average,
                completed.stream().map(Item::skill).distinct().sorted().toList());

        String note = goal.isEmpty() ? "Set an active career goal to see which skills to learn first; you can still add "
                + "items for any skill." : roadmap.get().roadmap().isEmpty()
                ? "Your roadmap has no skills left to develop: your resume already shows them all." : null;
        return new Plan(goal.map(CareerGoal::getId).orElse(null), goal.map(CareerGoal::getTargetRole).orElse(null),
                priorities, items, progress, impact(owner, roadmap, completed), note);
    }

    @Transactional
    public Item create(ItemRequest request) {
        UUID owner = currentUser.requireId();
        if (repository.countItems(owner) >= MAX_ITEMS) {
            throw new InvalidRequestException("You can keep at most " + MAX_ITEMS + " learning items");
        }
        Skill skill = null;
        if (request.skillId() != null) {
            skill = skills.findById(request.skillId()).orElseThrow(() -> ResourceNotFoundException.of("Skill", request.skillId()));
        } else if (request.skillName() != null && !request.skillName().isBlank()) {
            skill = skills.findFirstByNameIgnoreCase(request.skillName().strip()).orElse(null);
        } else {
            throw new InvalidRequestException("Choose a skill or type its name");
        }
        String skillName = skill != null ? skill.getName() : request.skillName().strip();
        Optional<CareerGoal> goal = activeGoal(owner);
        String priority = request.priority();
        if (priority == null) {
            Long skillId = skill == null ? null : skill.getId();
            priority = goal.flatMap(g -> roadmapService.roadmap(g.getId(), null).roadmap().stream()
                    .filter(s -> s.skillId().equals(skillId)).findFirst())
                    .map(s -> priorityFor(s.priority())).orElse("MEDIUM");
        }
        UUID id = UUID.randomUUID();
        repository.insertItem(id, owner, goal.map(CareerGoal::getId).orElse(null), skill == null ? null : skill.getId(),
                skillName, request.topic().strip(), priority, request.targetDate(), blankToNull(request.notes()), now());
        log.info("Learning item {} created", id);
        return item(id, owner);
    }

    @Transactional
    public Item update(UUID id, ItemUpdate update) {
        UUID owner = currentUser.requireId();
        requireItem(id, owner);
        repository.updateItem(id, owner, update.topic().strip(), update.priority(), update.progress(), update.targetDate(),
                blankToNull(update.notes()), now());
        return item(id, owner);
    }

    /**
     * Starts, completes or reopens an item. When it serves the active goal's roadmap, the roadmap skill
     * gets the same status, through the roadmap's own update (which checks the goal is the owner's and
     * the skill is on it). The resume is not touched.
     */
    @Transactional
    public Item changeStatus(UUID id, StatusRequest request) {
        UUID owner = currentUser.requireId();
        ItemRow row = requireItem(id, owner);
        repository.updateStatus(id, owner, request.status(), now());
        if (row.goalId() != null && row.skillId() != null) {
            try {
                roadmapService.setProgress(row.goalId(), row.skillId(), SkillProgressStatus.valueOf(request.status()));
            } catch (ResourceNotFoundException | InvalidRequestException notOnRoadmap) {
                // The goal was removed or the skill is no longer on its roadmap: the item still changes.
                log.debug("Learning item {} has no roadmap skill to update", id);
            }
        }
        return item(id, owner);
    }

    @Transactional
    public void delete(UUID id) {
        if (!repository.deleteItem(id, currentUser.requireId())) {
            throw ResourceNotFoundException.of("Learning item", id);
        }
    }

    @Transactional
    public Resource addResource(UUID itemId, ResourceRequest request) {
        UUID owner = currentUser.requireId();
        requireItem(itemId, owner);
        if (repository.countResources(itemId) >= MAX_RESOURCES_PER_ITEM) {
            throw new InvalidRequestException("An item can have at most " + MAX_RESOURCES_PER_ITEM + " resources");
        }
        UUID id = UUID.randomUUID();
        repository.insertResource(id, itemId, owner, request.title().strip(), request.url().strip(), request.type(),
                blankToNull(request.notes()), now());
        return repository.resource(id, owner).orElseThrow();
    }

    @Transactional
    public Resource updateResource(UUID id, ResourceRequest request) {
        UUID owner = currentUser.requireId();
        if (!repository.updateResource(id, owner, request.title().strip(), request.url().strip(), request.type(),
                blankToNull(request.notes()))) {
            throw ResourceNotFoundException.of("Learning resource", id);
        }
        return repository.resource(id, owner).orElseThrow();
    }

    @Transactional
    public void deleteResource(UUID id) {
        if (!repository.deleteResource(id, currentUser.requireId())) {
            throw ResourceNotFoundException.of("Learning resource", id);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Completed skills against the roadmap, the resume and the jobs the user saved; nothing is changed. */
    private Impact impact(UUID owner, Optional<RoadmapResponse> roadmap, List<Item> completed) {
        Set<String> onResume = resumeSkills(roadmap);
        List<String> notOnResume = completed.stream().map(Item::skill).distinct()
                .filter(skill -> !onResume.contains(skill.toLowerCase(Locale.ROOT))).sorted().toList();
        List<SavedJob> tracked = savedJobs.findByUserIdOrderByUpdatedAtDesc(owner);
        List<SkillDemand> demand = new ArrayList<>();
        for (String skill : notOnResume) {
            int jobs = (int) tracked.stream().filter(saved -> saved.getJob().getSkills().stream()
                    .anyMatch(s -> s.getName().equalsIgnoreCase(skill))).count();
            if (jobs > 0) {
                demand.add(new SkillDemand(skill, jobs));
            }
        }
        String note = notOnResume.isEmpty() ? null
                : "Job matches read your resume, so these completed skills count once you add them there. "
                        + "JMIP does not add them for you.";
        RoadmapResponse.Progress progress = roadmap.map(RoadmapResponse::progress).orElse(null);
        return new Impact(roadmap.map(RoadmapResponse::targetRole).orElse(null),
                progress == null ? null : progress.totalSkills(),
                progress == null ? null : progress.completed() + progress.onResume(),
                progress == null ? null : progress.percentComplete(), notOnResume, demand, note);
    }

    private Set<String> resumeSkills(Optional<RoadmapResponse> roadmap) {
        if (roadmap.isPresent()) {
            return roadmap.get().currentSkills().stream().map(s -> s.name().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        }
        return resumeService.currentProcessedResumeId()
                .map(id -> resumeService.requireCompletedResume(id).getSkills().stream()
                        .map(s -> s.getName().toLowerCase(Locale.ROOT)).collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    private List<Item> items(UUID owner, Optional<RoadmapResponse> roadmap) {
        Map<UUID, List<Resource>> resources = new LinkedHashMap<>();
        repository.resourceRows(owner).forEach(row -> resources.computeIfAbsent(row.itemId(), id -> new ArrayList<>()).add(row.resource()));
        Map<Long, String> roadmapStatus = new LinkedHashMap<>();
        roadmap.ifPresent(r -> r.roadmap().forEach(s -> roadmapStatus.put(s.skillId(),
                s.status() == null ? null : s.status().name())));
        return repository.items(owner).stream()
                .map(row -> toItem(row, resources.getOrDefault(row.id(), List.of()), roadmapStatus.get(row.skillId())))
                .toList();
    }

    private Item item(UUID id, UUID owner) {
        ItemRow row = requireItem(id, owner);
        List<Resource> resources = repository.resourceRows(owner).stream().filter(r -> r.itemId().equals(id))
                .map(LearningRepository.ResourceRow::resource).toList();
        String roadmapStatus = null;
        if (row.goalId() != null && row.skillId() != null) {
            roadmapStatus = activeGoal(owner).filter(g -> g.getId().equals(row.goalId()))
                    .flatMap(g -> roadmapService.roadmap(g.getId(), null).roadmap().stream()
                            .filter(s -> s.skillId().equals(row.skillId())).findFirst())
                    .map(s -> s.status() == null ? null : s.status().name()).orElse(null);
        }
        return toItem(row, resources, roadmapStatus);
    }

    private static Item toItem(ItemRow row, List<Resource> resources, String roadmapStatus) {
        return new Item(row.id(), row.goalId(), row.skillId(), row.skillName(), row.topic(), row.priority(), row.status(),
                row.progress(), row.targetDate(), row.notes(), row.createdAt(), row.updatedAt(), row.startedAt(),
                row.completedAt(), roadmapStatus, resources);
    }

    private ItemRow requireItem(UUID id, UUID owner) {
        return repository.item(id, owner).orElseThrow(() -> ResourceNotFoundException.of("Learning item", id));
    }

    private Optional<CareerGoal> activeGoal(UUID owner) {
        return goals.findByUserIdAndStatusOrderByUpdatedAtDesc(owner, CareerGoalStatus.ACTIVE).stream().findFirst();
    }

    /** The roadmap's own rank as a priority word: its top three HIGH, the next four MEDIUM, the rest LOW. */
    static String priorityFor(int rank) {
        return rank <= 3 ? "HIGH" : rank <= 7 ? "MEDIUM" : "LOW";
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
