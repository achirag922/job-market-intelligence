package com.jmip.service.workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmip.common.exception.ConflictException;
import com.jmip.common.exception.InvalidRequestException;
import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.dto.JobSearchCriteria;
import com.jmip.dto.JobSummaryResponse;
import com.jmip.dto.PagedResponse;
import com.jmip.dto.SkillResponse;
import com.jmip.dto.resume.ResumeMatchResponse;
import com.jmip.dto.saved.SavedJobResponse;
import com.jmip.dto.workspace.WorkspaceDtos.HiddenJob;
import com.jmip.dto.workspace.WorkspaceDtos.MatchHint;
import com.jmip.dto.workspace.WorkspaceDtos.Matches;
import com.jmip.dto.workspace.WorkspaceDtos.SavedSearch;
import com.jmip.dto.workspace.WorkspaceDtos.SavedSearchRequest;
import com.jmip.dto.workspace.WorkspaceDtos.ViewedJob;
import com.jmip.dto.workspace.WorkspaceDtos.Workspace;
import com.jmip.entity.ApplicationStatus;
import com.jmip.repository.JobRepository;
import com.jmip.repository.WorkspaceRepository;
import com.jmip.repository.WorkspaceRepository.Timed;
import com.jmip.service.JobService;
import com.jmip.service.PersonalizedFeedService;
import com.jmip.service.auth.CurrentUser;
import com.jmip.service.resume.ResumeMatchService;
import com.jmip.service.resume.ResumeService;
import com.jmip.service.saved.SavedJobService;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * V9.14: the signed-in user's job-search workspace. It adds only what JMIP did not keep before
 * (hidden jobs, recently viewed jobs, saved searches) and otherwise composes existing features: the
 * V7.2/V8.5 saved jobs with statuses, notes and follow-ups, the V9.2 personalized feed, the job search
 * and the existing skill match for explanations and match ordering. Nothing here changes how a match
 * is calculated.
 */
@Service
public class WorkspaceService {

    static final int MAX_HIDDEN = 500;
    static final int MAX_SAVED_SEARCHES = 20;
    static final int MATCH_CANDIDATES = 300;
    static final int MAX_MATCH_HINTS = 50;
    private static final int SHOWN = 10;
    /** The Job Explorer's own query parameters; nothing else can be stored in a saved search. */
    static final Set<String> SEARCH_KEYS = Set.of("q", "category", "skill", "location", "company", "employmentType",
            "experience", "currency", "salaryMin", "salaryMax", "locationStated", "title", "order", "size");

    private final CurrentUser currentUser;
    private final WorkspaceRepository repository;
    private final JobRepository jobs;
    private final JobService jobService;
    private final SavedJobService savedJobs;
    private final PersonalizedFeedService feed;
    private final ResumeService resumeService;
    private final ResumeMatchService matchService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WorkspaceService(CurrentUser currentUser, WorkspaceRepository repository, JobRepository jobs, JobService jobService,
                            SavedJobService savedJobs, PersonalizedFeedService feed, ResumeService resumeService,
                            ResumeMatchService matchService, ObjectMapper objectMapper, Clock clock) {
        this.currentUser = currentUser;
        this.repository = repository;
        this.jobs = jobs;
        this.jobService = jobService;
        this.savedJobs = savedJobs;
        this.feed = feed;
        this.resumeService = resumeService;
        this.matchService = matchService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Workspace workspace() {
        UUID owner = currentUser.requireId();
        List<SavedJobResponse> tracked = savedJobs.list(null);
        List<SavedJobResponse> followUps = tracked.stream()
                .filter(s -> s.followUpOn() != null && s.status() != ApplicationStatus.REJECTED && s.status() != ApplicationStatus.WITHDRAWN)
                .sorted(Comparator.comparing(SavedJobResponse::followUpOn)).toList();
        List<Timed> viewed = repository.recentViews(owner, SHOWN);
        List<Timed> hidden = repository.hidden(owner, 50);
        return new Workspace(
                feed.feed(6).jobs(),
                tracked.stream().filter(s -> s.status() == ApplicationStatus.SAVED).toList(),
                tracked.stream().filter(s -> s.status() == ApplicationStatus.APPLIED || s.status() == ApplicationStatus.INTERVIEW
                        || s.status() == ApplicationStatus.OFFER).toList(),
                followUps,
                withJobs(viewed).entrySet().stream().map(e -> new ViewedJob(e.getValue(), e.getKey().at())).toList(),
                withJobs(hidden).entrySet().stream().map(e -> new HiddenJob(e.getValue(), e.getKey().at())).toList(),
                searches(owner));
    }

    // ------------------------------------------------------------------ hidden and viewed

    @Transactional
    public void hide(long jobId) {
        UUID owner = currentUser.requireId();
        requireJob(jobId);
        if (repository.hiddenIds(owner).size() >= MAX_HIDDEN && !repository.hiddenIds(owner).contains(jobId)) {
            throw new InvalidRequestException("You can hide at most " + MAX_HIDDEN + " jobs; unhide some first");
        }
        repository.hide(owner, jobId, now());
    }

    @Transactional
    public void unhide(long jobId) {
        if (!repository.unhide(currentUser.requireId(), jobId)) {
            throw ResourceNotFoundException.of("Hidden job", jobId);
        }
    }

    @Transactional
    public void recordView(long jobId) {
        UUID owner = currentUser.requireId();
        requireJob(jobId);
        repository.recordView(owner, jobId, now());
    }

    public List<Long> hiddenIds() {
        return repository.hiddenIds(currentUser.requireId());
    }

    // ------------------------------------------------------------------ saved searches

    @Transactional(readOnly = true)
    public List<SavedSearch> searches() {
        return searches(currentUser.requireId());
    }

    @Transactional
    public SavedSearch saveSearch(SavedSearchRequest request) {
        UUID owner = currentUser.requireId();
        String name = request.name().strip();
        Map<String, String> filters = new LinkedHashMap<>();
        request.filters().forEach((key, value) -> {
            if (!SEARCH_KEYS.contains(key)) {
                throw new InvalidRequestException("'" + key + "' is not a search filter");
            }
            if (value != null && !value.isBlank()) {
                if (value.length() > 200) {
                    throw new InvalidRequestException("Filter values can be at most 200 characters");
                }
                filters.put(key, value.strip());
            }
        });
        if (repository.searches(owner).size() >= MAX_SAVED_SEARCHES) {
            throw new InvalidRequestException("You can keep at most " + MAX_SAVED_SEARCHES + " saved searches");
        }
        if (repository.searchNameTaken(owner, name)) {
            throw new ConflictException("You already have a saved search called \"" + name + "\"");
        }
        UUID id = UUID.randomUUID();
        repository.insertSearch(id, owner, name, json(filters), now());
        return searches(owner).stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteSearch(UUID id) {
        if (!repository.deleteSearch(id, currentUser.requireId())) {
            throw ResourceNotFoundException.of("Saved search", id);
        }
    }

    // ------------------------------------------------------------------ matching

    /** Why each job matches the current resume, from the existing skill match; empty without a resume. */
    @Transactional(readOnly = true)
    public Matches matches(List<Long> jobIds) {
        currentUser.requireId();
        if (jobIds.size() > MAX_MATCH_HINTS) {
            throw new InvalidRequestException("Ask for at most " + MAX_MATCH_HINTS + " jobs at a time");
        }
        UUID resume = resumeService.currentProcessedResumeId().orElse(null);
        if (resume == null) {
            return new Matches(Map.of(), "Upload or build a resume to see how well each job matches you.");
        }
        Map<Long, MatchHint> hints = new LinkedHashMap<>();
        matchService.matchJobs(resume, jobIds).forEach((jobId, match) -> hints.put(jobId, hint(match)));
        return new Matches(hints, null);
    }

    /**
     * The search ordered by the existing skill match with the current resume: up to
     * {@value #MATCH_CANDIDATES} matching jobs, newest first, ranked best match first, then paged.
     */
    @Transactional(readOnly = true)
    public PagedResponse<JobSummaryResponse> searchByMatch(JobSearchCriteria criteria, Pageable pageable, boolean excludeHidden) {
        UUID owner = currentUser.requireId();
        UUID resume = resumeService.currentProcessedResumeId()
                .orElseThrow(() -> new InvalidRequestException("Upload or build a resume to sort by match"));
        List<Long> candidates = jobService.matchingIds(criteria, excludeHidden ? repository.hiddenIds(owner) : List.of(),
                MATCH_CANDIDATES);
        Map<Long, ResumeMatchResponse> matches = candidates.isEmpty() ? Map.of() : matchService.matchJobs(resume, candidates);
        List<Long> ranked = candidates.stream()
                .sorted(Comparator.comparing((Long id) -> percentage(matches.get(id))).reversed()).toList();
        int from = (int) Math.min(pageable.getOffset(), ranked.size());
        int to = Math.min(from + pageable.getPageSize(), ranked.size());
        List<JobSummaryResponse> content = jobService.summaries(ranked.subList(from, to));
        return PagedResponse.of(new PageImpl<>(content, pageable, ranked.size()));
    }

    // ------------------------------------------------------------------ helpers

    private static double percentage(ResumeMatchResponse match) {
        return match == null || match.matchPercentage() == null ? -1 : match.matchPercentage();
    }

    private static MatchHint hint(ResumeMatchResponse match) {
        return new MatchHint(match.matchPercentage(), names(match.matchedSkills()), names(match.missingSkills()));
    }

    private static List<String> names(List<SkillResponse> skills) {
        return skills == null ? List.of() : skills.stream().map(SkillResponse::name).limit(5).toList();
    }

    /** The rows paired with their job summaries, in the rows' order; deleted jobs drop out. */
    private Map<Timed, JobSummaryResponse> withJobs(List<Timed> rows) {
        Map<Long, JobSummaryResponse> summaries = jobService.summaries(rows.stream().map(Timed::jobId).toList()).stream()
                .collect(Collectors.toMap(JobSummaryResponse::id, Function.identity()));
        Map<Timed, JobSummaryResponse> paired = new LinkedHashMap<>();
        rows.stream().filter(row -> summaries.containsKey(row.jobId())).forEach(row -> paired.put(row, summaries.get(row.jobId())));
        return paired;
    }

    private List<SavedSearch> searches(UUID owner) {
        return repository.searches(owner).stream()
                .map(row -> new SavedSearch(row.id(), row.name(), read(row.filters()), row.createdAt())).toList();
    }

    private void requireJob(long jobId) {
        if (!jobs.existsById(jobId)) {
            throw ResourceNotFoundException.of("Job", jobId);
        }
    }

    private String json(Map<String, String> filters) {
        try {
            return objectMapper.writeValueAsString(filters);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Saved search could not be written", invalid);
        }
    }

    private Map<String, String> read(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (JsonProcessingException invalid) {
            return Map.of();
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
