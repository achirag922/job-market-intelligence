package com.jmip.controller;

import com.jmip.dto.PagedResponse;
import com.jmip.dto.etl.EtlRunResponse;
import com.jmip.service.EtlRunService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only ETL monitoring over the Spring Batch job repository. Nothing here starts,
 * stops or restarts a run.
 */
@RestController
@RequestMapping("/api/etl/runs")
@Validated
public class EtlRunController {

    private static final Logger log = LoggerFactory.getLogger(EtlRunController.class);

    private final EtlRunService etlRunService;

    public EtlRunController(EtlRunService etlRunService) {
        this.etlRunService = etlRunService;
    }

    /**
     * Run history, newest first.
     *
     * @param job only runs of this Spring Batch job, e.g. {@code ingestJobPostings}
     */
    @GetMapping
    public ResponseEntity<PagedResponse<EtlRunResponse>> runs(
            @RequestParam(required = false) @Size(max = 100) String job,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        log.info("GET /api/etl/runs job={} page={} size={}", job, page, size);
        return ResponseEntity.ok(etlRunService.runs(job, page, size));
    }

    /** The most recent run. 404 when the ETL has never run against this database. */
    @GetMapping("/latest")
    public ResponseEntity<EtlRunResponse> latest(@RequestParam(required = false) @Size(max = 100) String job) {
        log.info("GET /api/etl/runs/latest job={}", job);
        return ResponseEntity.ok(etlRunService.latest(job));
    }
}
