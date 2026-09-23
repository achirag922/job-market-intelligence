package com.jmip.controller;

import com.jmip.common.exception.InvalidRequestException;
import com.jmip.dto.JobDetailResponse;
import com.jmip.dto.JobOrder;
import com.jmip.dto.JobSearchCriteria;
import com.jmip.dto.JobSummaryResponse;
import com.jmip.dto.PagedResponse;
import com.jmip.dto.analytics.SalaryRangeResponse;
import com.jmip.service.JobService;
import com.jmip.service.analytics.SalaryAnalyticsService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/jobs")
@Validated
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    private final JobService jobService;
    private final SalaryAnalyticsService salaryAnalyticsService;

    public JobController(JobService jobService, SalaryAnalyticsService salaryAnalyticsService) {
        this.jobService = jobService;
        this.salaryAnalyticsService = salaryAnalyticsService;
    }

    /**
     * Searches postings. Every filter is optional and they combine with AND.
     *
     * <p>Ordering, two ways. {@code order} takes a named ordering — {@code newest},
     * {@code oldest}, {@code relevance}, {@code salary-high}, {@code salary-low},
     * {@code title}, {@code company} — and is what the frontend uses. {@code sort} keeps
     * working as before for the plain fields {@code postedDate}, {@code title},
     * {@code salaryMin}, {@code salaryMax} and {@code createdAt}. When both are given,
     * {@code order} wins.
     *
     * <p>With neither, results are newest first with undated postings last.
     */
    @GetMapping
    public ResponseEntity<PagedResponse<JobSummaryResponse>> search(
            @Valid JobSearchCriteria criteria,
            @RequestParam(required = false) String order,
            @PageableDefault(size = 20) Pageable pageable) {
        JobOrder resolved = resolveOrder(order);
        log.info("GET /api/jobs page={} size={} order={} filters={}",
                pageable.getPageNumber(), pageable.getPageSize(), resolved, criteria);
        return ResponseEntity.ok(jobService.search(criteria, pageable, resolved));
    }

    /**
     * The currencies salaries are stated in, with the range seen in each.
     *
     * <p>What the salary filter offers. Read from the postings rather than listed in the
     * frontend, so the filter can never offer a currency no posting uses, and the ranges
     * give the amount fields a sensible hint. A currency needs only one posting to be
     * listed here; unlike the salary analytics, this is a menu, not a statistic.
     */
    @GetMapping("/salary-currencies")
    public ResponseEntity<List<SalaryRangeResponse>> salaryCurrencies() {
        log.info("GET /api/jobs/salary-currencies");
        return ResponseEntity.ok(salaryAnalyticsService.salaryRanges(null, 1));
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobDetailResponse> findById(@PathVariable @Positive Long id) {
        log.info("GET /api/jobs/{}", id);
        return ResponseEntity.ok(jobService.findById(id));
    }

    /** An unknown ordering is a 400 naming the valid ones, not a silent fall back. */
    private static JobOrder resolveOrder(String order) {
        if (order == null || order.isBlank()) {
            return null;
        }
        return JobOrder.fromSlug(order).orElseThrow(() -> new InvalidRequestException(
                "Unknown order '" + order + "'. Valid orders: "
                        + String.join(", ", Arrays.stream(JobOrder.values()).map(JobOrder::slug).toList())));
    }
}
