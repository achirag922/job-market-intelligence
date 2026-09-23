package com.jmip.service;

import com.jmip.common.exception.InvalidRequestException;
import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.dto.JobDetailResponse;
import com.jmip.dto.JobOrder;
import com.jmip.dto.JobSearchCriteria;
import com.jmip.dto.JobSummaryResponse;
import com.jmip.dto.PagedResponse;
import com.jmip.dto.SkillResponse;
import com.jmip.entity.Job;
import com.jmip.entity.JobClassificationSignal;
import com.jmip.mapper.JobMapper;
import com.jmip.repository.JobClassificationSignalRepository;
import com.jmip.repository.JobRepository;
import com.jmip.repository.JobSpecifications;
import com.jmip.repository.projection.JobSkillRow;
import com.jmip.service.analytics.ExperienceBucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
    private final JobClassificationSignalRepository signalRepository;

    public JobService(JobRepository jobRepository, JobMapper jobMapper,
                      JobClassificationSignalRepository signalRepository) {
        this.jobRepository = jobRepository;
        this.jobMapper = jobMapper;
        this.signalRepository = signalRepository;
    }

    /** The pre-V6.2 entry point: plain fields via {@code sort}, newest first by default. */
    public PagedResponse<JobSummaryResponse> search(JobSearchCriteria criteria, Pageable pageable) {
        return search(criteria, pageable, null);
    }

    /**
     * Searches postings.
     *
     * @param order a named ordering. When given it wins over any {@code sort} in the
     *              pageable, because the two cannot both decide the order and the named
     *              one is the more specific request. Null keeps the previous behaviour
     */
    public PagedResponse<JobSummaryResponse> search(JobSearchCriteria criteria, Pageable pageable,
                                                    JobOrder order) {
        validate(criteria, order);

        Specification<Job> specification = toSpecification(criteria);
        Pageable resolved;
        if (order != null) {
            // The named orderings carry their own ORDER BY; a leftover Sort would be
            // appended after it and silently change the tiebreak.
            resolved = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
            specification = specification.and(orderingFor(order, criteria));
        } else {
            resolved = SORTABLE.apply(pageable);
            if (resolved.getSort().isUnsorted()) {
                specification = specification.and(JobSpecifications.newestFirst());
            }
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
        Job job = jobRepository.findDetailById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Job", id));
        // One extra query, only on the detail view, and only when there is a category to
        // explain. The list view never pays for it.
        List<JobClassificationSignal> signals = job.getJobCategory() == null
                ? List.of() : signalRepository.findByJobId(id);
        return jobMapper.toDetail(job, signals);
    }

    /**
     * Rejects combinations that have no honest answer, rather than quietly answering a
     * different question.
     */
    private static void validate(JobSearchCriteria criteria, JobOrder order) {
        if (criteria.hasUnscopedSalaryFilter()) {
            throw new InvalidRequestException(
                    "A salary filter needs a currency. Salaries are stated in several currencies "
                            + "and this dataset has no exchange rates, so a bare amount cannot be compared.");
        }
        if (criteria.salaryMin() != null && criteria.salaryMax() != null
                && criteria.salaryMin().compareTo(criteria.salaryMax()) > 0) {
            throw new InvalidRequestException("salaryMin cannot be greater than salaryMax");
        }
        if (order != null && order.needsCurrency() && criteria.currency() == null) {
            throw new InvalidRequestException(
                    "Sorting by salary needs a currency filter, because salaries in different "
                            + "currencies cannot be ranked against each other.");
        }
    }

    private static Specification<Job> orderingFor(JobOrder order, JobSearchCriteria criteria) {
        return switch (order) {
            case NEWEST -> JobSpecifications.newestFirst();
            case OLDEST -> JobSpecifications.oldestFirst();
            // With nothing to score against every posting ties, so relevance would be an
            // arbitrary order dressed up as a ranking. Newest is the honest fallback.
            case RELEVANCE -> criteria.hasQuery()
                    ? JobSpecifications.byRelevance(criteria.q())
                    : JobSpecifications.newestFirst();
            case SALARY_HIGH -> JobSpecifications.bySalary(true);
            case SALARY_LOW -> JobSpecifications.bySalary(false);
            case TITLE -> JobSpecifications.byTitle();
            case COMPANY -> JobSpecifications.byCompany();
        };
    }

    private Specification<Job> toSpecification(JobSearchCriteria criteria) {
        Specification<Job> specification = JobSpecifications.all();
        if (criteria.hasQuery()) {
            specification = specification.and(JobSpecifications.matchesQuery(criteria.q()));
        }
        if (criteria.hasTitle()) {
            specification = specification.and(JobSpecifications.titleContains(criteria.title()));
        }
        if (criteria.hasLocationFilter()) {
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
        if (criteria.hasCategory()) {
            specification = specification.and(JobSpecifications.categoryIs(criteria.category()));
        }
        if (criteria.hasExperience()) {
            specification = specification.and(
                    JobSpecifications.experienceIn(ExperienceBucket.fromSlug(criteria.experience())));
        }
        if (criteria.hasSalaryFilter()) {
            specification = specification.and(JobSpecifications.salaryOverlaps(
                    criteria.currency(), criteria.salaryMin(), criteria.salaryMax()));
        } else if (criteria.currency() != null) {
            // A currency with no bounds still narrows to postings paid in it — which is also
            // what makes a salary ordering meaningful.
            specification = specification.and(JobSpecifications.currencyIs(criteria.currency()));
        }
        if (criteria.hasLocationPresenceFilter()) {
            specification = specification.and(
                    JobSpecifications.locationStated(criteria.locationStated()));
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
