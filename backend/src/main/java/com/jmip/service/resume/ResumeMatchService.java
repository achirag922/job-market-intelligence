package com.jmip.service.resume;

import com.jmip.dto.resume.MatchPreferences;
import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.dto.SkillResponse;
import com.jmip.dto.resume.ResumeMatchResponse;
import com.jmip.dto.resume.ResumeRecommendationResponse;
import com.jmip.entity.Job;
import com.jmip.entity.Resume;
import com.jmip.entity.Skill;
import com.jmip.mapper.JobMapper;
import com.jmip.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Compares a resume's skills against a job's required skills.
 *
 * <p>Both sides are rows from the same {@code skills} table, so the comparison is by
 * skill id. No string matching happens here at all, and the two sides cannot disagree
 * about what counts as the same skill.
 *
 * <p>The score is deliberately simple and deterministic: the share of the job's listed
 * skills that the resume shows. It is not weighted, not learned, and not a prediction of
 * anything. A job that lists no skills has no denominator, and gets no score rather than
 * a misleading zero or a division by zero.
 */
@Service
public class ResumeMatchService {

    private static final Logger log = LoggerFactory.getLogger(ResumeMatchService.class);

    private final ResumeService resumeService;
    private final JobRepository jobRepository;
    private final JobMapper jobMapper;
    private final JobMatchScorer scorer;
    private final com.jmip.repository.MatchPreferencesRepository preferencesRepository;
    /** V9.3: the owner's active career goal, for goal alignment. */
    private final MatchGoals goals;

    /** V8.3: candidates re-ranked by the full score are drawn from this many times the limit. */
    static final int CANDIDATE_POOL_FACTOR = 5;

    public ResumeMatchService(ResumeService resumeService, JobRepository jobRepository, JobMapper jobMapper,
                              JobMatchScorer scorer, com.jmip.repository.MatchPreferencesRepository preferencesRepository,
                              MatchGoals goals) {
        this.goals = goals;
        this.resumeService = resumeService;
        this.jobRepository = jobRepository;
        this.jobMapper = jobMapper;
        this.scorer = scorer;
        this.preferencesRepository = preferencesRepository;
    }

    @Transactional(readOnly = true)
    public ResumeMatchResponse match(UUID resumeId, Long jobId) {
        return matchWithContext(resumeId, jobId).match();
    }

    /** V7.3: the same match, with the resume and job it was computed from, for deeper analysis. */
    public record MatchContext(Resume resume, Job job, ResumeMatchResponse match) {
    }

    @Transactional(readOnly = true)
    public MatchContext matchWithContext(UUID resumeId, Long jobId) {
        Resume resume = resumeService.requireCompletedResume(resumeId);
        Job job = jobRepository.findDetailById(jobId)
                .orElseThrow(() -> ResourceNotFoundException.of("Job", jobId));

        return new MatchContext(resume, job, compare(resume, job, preferencesOf(resume), goalOf(resume)));
    }

    /**
     * Returns the highest-overlap postings for a completed resume. A candidate must list
     * at least one skill and share at least one of those skills with the resume; a blank
     * score is never represented as a recommendation.
     */
    @Transactional(readOnly = true)
    public List<ResumeRecommendationResponse> recommend(UUID resumeId, int limit) {
        Resume resume = resumeService.requireCompletedResume(resumeId);
        Set<Long> resumeSkillIds = idsOf(resume.getSkills());
        if (resumeSkillIds.isEmpty()) {
            return List.of();
        }

        // V8.3: the best skill matches form the candidate pool, which the full score then ranks.
        List<Long> rankedIds = jobRepository.findRecommendationJobIds(resumeSkillIds,
                PageRequest.of(0, limit * CANDIDATE_POOL_FACTOR));
        if (rankedIds.isEmpty()) {
            return List.of();
        }

        Map<Long, Job> jobsById = jobRepository.findRecommendationDetailsByIdIn(rankedIds).stream()
                .collect(Collectors.toMap(Job::getId, job -> job));

        // SQL establishes the skill rank. An IN clause is unordered, so retain that order here
        // rather than accidentally turning equal scores into database-dependent results.
        // V8.3: the overall score then ranks, and the stable sort keeps the skill order for ties,
        // so without preferences the result is exactly the V6.3 ranking.
        MatchPreferences preferences = preferencesOf(resume);
        JobMatchScorer.GoalContext goal = goalOf(resume);
        return rankedIds.stream()
                .map(jobsById::get)
                .filter(java.util.Objects::nonNull)
                .map(job -> toRecommendation(job, compare(resume, job, preferences, goal)))
                .sorted(Comparator.comparing(ResumeRecommendationResponse::overallMatchPercentage,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .toList();
    }

    /**
     * V8.5: the same comparison of one of the signed-in user's resumes against several jobs at
     * once, by job id, in one detail query. Used by application intelligence.
     */
    @Transactional(readOnly = true)
    public Map<Long, ResumeMatchResponse> matchJobs(UUID resumeId, java.util.Collection<Long> jobIds) {
        if (jobIds.isEmpty()) {
            return Map.of();
        }
        Resume resume = resumeService.requireCompletedResume(resumeId);
        MatchPreferences preferences = preferencesOf(resume);
        JobMatchScorer.GoalContext goal = goalOf(resume);
        return jobRepository.findRecommendationDetailsByIdIn(jobIds).stream()
                .collect(Collectors.toMap(Job::getId, job -> compare(resume, job, preferences, goal)));
    }

    /** The one V3 comparison implementation shared by direct matches and recommendations. */
    private ResumeMatchResponse compare(Resume resume, Job job, MatchPreferences preferences,
                                        JobMatchScorer.GoalContext goal) {

        Set<Long> resumeSkillIds = idsOf(resume.getSkills());
        Set<Long> jobSkillIds = idsOf(job.getSkills());

        List<Skill> matched = job.getSkills().stream()
                .filter(skill -> resumeSkillIds.contains(skill.getId()))
                .toList();
        List<Skill> missing = job.getSkills().stream()
                .filter(skill -> !resumeSkillIds.contains(skill.getId()))
                .toList();
        List<Skill> resumeOnly = resume.getSkills().stream()
                .filter(skill -> !jobSkillIds.contains(skill.getId()))
                .toList();

        int totalJobSkills = jobSkillIds.size();
        Double matchPercentage = totalJobSkills == 0
                ? null
                : Math.round(matched.size() * 1000.0 / totalJobSkills) / 10.0;
        String matchNote = totalJobSkills == 0
                ? "This posting lists no skills, so there is nothing to match against"
                : null;

        log.debug("Resume {} against job {}: {} of {} job skills matched",
                resume.getId(), job.getId(), matched.size(), totalJobSkills);

        return new ResumeMatchResponse(
                resume.getId(),
                job.getId(),
                job.getTitle(),
                job.getCompany().getName(),
                job.getJobCategory(),
                matchPercentage,
                matchNote,
                totalJobSkills,
                resumeSkillIds.size(),
                matched.size(),
                missing.size(),
                toSortedResponses(matched),
                toSortedResponses(missing),
                toSortedResponses(resumeOnly),
                scorer.score(resumeSkillIds, job, preferences, goal));
    }

    /** V9.3: the resume owner's active goal, or none (also when no goal service is wired, as in unit tests). */
    private JobMatchScorer.GoalContext goalOf(Resume resume) {
        JobMatchScorer.GoalContext goal = goals == null ? null : goals.forUser(resume.getUserId());
        return goal == null ? JobMatchScorer.GoalContext.NONE : goal;
    }

    /** The resume owner's preferences: the resume is already owner-checked, so this is the signed-in user's. */
    private MatchPreferences preferencesOf(Resume resume) {
        return resume.getUserId() == null ? MatchPreferences.NONE : preferencesRepository.find(resume.getUserId());
    }

    private ResumeRecommendationResponse toRecommendation(Job job, ResumeMatchResponse match) {
        // Candidates are constrained in SQL to have skills and a non-zero overlap, so this
        // cannot be null. Keeping the guard makes the API robust if the query changes.
        if (match.matchPercentage() == null) {
            throw new IllegalStateException("A recommendation must have a skill-match percentage");
        }
        return new ResumeRecommendationResponse(
                match.jobId(),
                match.jobTitle(),
                match.companyName(),
                jobMapper.toLocation(job.getLocation()),
                match.jobCategory(),
                match.matchPercentage(),
                match.matchedSkills(),
                match.missingSkills(),
                match.breakdown().overallPercentage(),
                match.breakdown());
    }

    private static Set<Long> idsOf(Set<Skill> skills) {
        return skills.stream().map(Skill::getId).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<SkillResponse> toSortedResponses(List<Skill> skills) {
        return skills.stream()
                .map(jobMapper::toSkill)
                .sorted(Comparator.comparing(SkillResponse::name))
                .toList();
    }
}
