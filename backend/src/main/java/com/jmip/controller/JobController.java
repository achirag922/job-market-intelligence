package com.jmip.controller;

import com.jmip.dto.JobDetailResponse;
import com.jmip.dto.JobSearchCriteria;
import com.jmip.dto.JobSummaryResponse;
import com.jmip.dto.PagedResponse;
import com.jmip.service.JobService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs")
@Validated
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    /**
     * Searches postings. Every filter is optional and they combine with AND.
     *
     * <p>Sortable fields: {@code postedDate}, {@code title}, {@code salaryMin},
     * {@code salaryMax}, {@code createdAt}.
     *
     * <p>With no sort given, results are newest first with undated postings last. That
     * default is applied in the query rather than declared here, because it needs an
     * expression a {@code Sort} cannot carry.
     */
    @GetMapping
    public ResponseEntity<PagedResponse<JobSummaryResponse>> search(
            @Valid JobSearchCriteria criteria,
            @PageableDefault(size = 20) Pageable pageable) {
        log.info("GET /api/jobs page={} size={} filters={}",
                pageable.getPageNumber(), pageable.getPageSize(), criteria);
        return ResponseEntity.ok(jobService.search(criteria, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobDetailResponse> findById(@PathVariable @Positive Long id) {
        log.info("GET /api/jobs/{}", id);
        return ResponseEntity.ok(jobService.findById(id));
    }
}
