package com.jmip.service;

import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.dto.JobDetailResponse;
import com.jmip.dto.JobSearchCriteria;
import com.jmip.dto.JobSummaryResponse;
import com.jmip.dto.PagedResponse;
import com.jmip.dto.SkillResponse;
import com.jmip.entity.Job;
import com.jmip.mapper.JobMapper;
import com.jmip.repository.JobRepository;
import com.jmip.repository.JobSpecifications;
import com.jmip.repository.projection.JobSkillRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private static final SortWhitelist SORTABLE = SortWhitelist.of(Map.of(
            "postedDate", "postedDate",
            "title", "title",
            "salaryMin", "salaryMin",
            "salaryMax", "salaryMax",
            "createdAt", "createdAt"));

    private final JobRepository jobRepository;
    private final JobMapper jobMapper;

    public JobService(JobRepository jobRepository, JobMapper jobMapper) {
        this.jobRepository = jobRepository;
        this.jobMapper = jobMapper;
    }

    public PagedResponse<JobSummaryResponse> search(JobSearchCriteria criteria, Pageable pageable) {
        Pageable resolved = SORTABLE.apply(pageable);
        Specification<Job> specification = toSpecification(criteria);
        if (resolved.getSort().isUnsorted()) {
            specification = specification.and(JobSpecifications.newestFirst());
        }
        Page<Job> page = jobRepository.findAll(specification, resolved);

        log.debug("Job search {} returned {} of {} matches", criteria, page.getNumberOfElements(),
                page.getTotalElements());

        Map<Long, List<SkillResponse>> skillsByJob = loadSkills(page.getContent());
        List<JobSummaryResponse> content = page.getContent().stream()
                .map(job -> jobMapper.toSummary(job, skillsByJob.getOrDefault(job.getId(), List.of())))
                .toList();
        return PagedResponse.of(content, page);
    }

    public JobDetailResponse findById(Long id) {
        return jobRepository.findDetailById(id)
                .map(jobMapper::toDetail)
                .orElseThrow(() -> ResourceNotFoundException.of("Job", id));
    }

    private Specification<Job> toSpecification(JobSearchCriteria criteria) {
        Specification<Job> specification = JobSpecifications.all();
        if (criteria.hasTitle()) {
            specification = specification.and(JobSpecifications.titleContains(criteria.title()));
        }
        if (criteria.hasLocation()) {
            specification = specification.and(JobSpecifications.locationMatches(criteria.location()));
        }
        if (criteria.hasCompany()) {
            specification = specification.and(JobSpecifications.companyNameContains(criteria.company()));
        }
        if (criteria.hasSkill()) {
            specification = specification.and(JobSpecifications.hasSkill(criteria.skill()));
        }
        if (criteria.hasEmploymentType()) {
            specification = specification.and(JobSpecifications.employmentTypeIs(criteria.employmentType()));
        }
        return specification;
    }

    /** One query for the whole page, rather than one lazy load per job. */
    private Map<Long, List<SkillResponse>> loadSkills(List<Job> jobs) {
        if (jobs.isEmpty()) {
            return Map.of();
        }
        List<Long> jobIds = jobs.stream().map(Job::getId).toList();
        Map<Long, List<SkillResponse>> byJob = new LinkedHashMap<>();
        for (JobSkillRow row : jobRepository.findSkillsForJobs(jobIds)) {
            byJob.computeIfAbsent(row.jobId(), key -> new ArrayList<>()).add(jobMapper.toSkill(row.skill()));
        }
        return byJob;
    }
}
