package com.jmip.service.resume;

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

    public ResumeMatchService(ResumeService resumeService, JobRepository jobRepository, JobMapper jobMapper) {
        this.resumeService = resumeService;
        this.jobRepository = jobRepository;
        this.jobMapper = jobMapper;
    }

    @Transactional(readOnly = true)
    public ResumeMatchResponse match(UUID resumeId, Long jobId) {
        Resume resume = resumeService.requireCompletedResume(resumeId);
        Job job = jobRepository.findDetailById(jobId)
                .orElseThrow(() -> ResourceNotFoundException.of("Job", jobId));

        return compare(resume, job);
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

        List<Long> rankedIds = jobRepository.findRecommendationJobIds(resumeSkillIds, PageRequest.of(0, limit));
        if (rankedIds.isEmpty()) {
            return List.of();
        }

        Map<Long, Job> jobsById = jobRepository.findRecommendationDetailsByIdIn(rankedIds).stream()
                .collect(Collectors.toMap(Job::getId, job -> job));

        // SQL establishes the rank. An IN clause is unordered, so retain that order here
        // rather than accidentally turning equal scores into database-dependent results.
        return rankedIds.stream()
                .map(jobsById::get)
                .filter(java.util.Objects::nonNull)
                .map(job -> toRecommendation(job, compare(resume, job)))
                .toList();
    }

    /** The one V3 comparison implementation shared by direct matches and recommendations. */
    private ResumeMatchResponse compare(Resume resume, Job job) {

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
                toSortedResponses(resumeOnly));
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
                match.missingSkills());
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
