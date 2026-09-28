package com.jmip.controller;

import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.dto.etl.JobSourceResponse;
import com.jmip.repository.JobSourceRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * V8.1: where JMIP's postings come from. Read-only by design: sources are registered by the
 * ETL, and nothing here lets a user change what is ingested. Signed-in only, like all of /api.
 */
@RestController
@RequestMapping("/api/job-sources")
public class JobSourceController {

    static final int RECENT_RUNS = 10;

    private final JobSourceRepository repository;

    public JobSourceController(JobSourceRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<JobSourceResponse> sources() {
        return repository.findAll();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public JobSourceResponse source(@PathVariable long id) {
        return repository.findById(id)
                .map(source -> source.withRecentRuns(repository.findRecentRuns(id, RECENT_RUNS)))
                .orElseThrow(() -> ResourceNotFoundException.of("Job source", id));
    }
}
