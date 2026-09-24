package com.jmip.controller;

import com.jmip.dto.alert.JobAlertRequest;
import com.jmip.dto.alert.JobAlertResponse;
import com.jmip.dto.alert.JobAlertStatusRequest;
import com.jmip.service.alert.JobAlertService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Saved job-search alerts of the signed-in account (V7.1). Signed-in only, like all of /api;
 * the owner always comes from the session, never from the request.
 */
@RestController
@RequestMapping("/api/job-alerts")
public class JobAlertController {

    private final JobAlertService jobAlertService;

    public JobAlertController(JobAlertService jobAlertService) {
        this.jobAlertService = jobAlertService;
    }

    @PostMapping
    public ResponseEntity<JobAlertResponse> create(@Valid @RequestBody JobAlertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(jobAlertService.create(request));
    }

    /** Newest first. */
    @GetMapping
    public List<JobAlertResponse> list() {
        return jobAlertService.list();
    }

    @GetMapping("/{id}")
    public JobAlertResponse get(@PathVariable UUID id) {
        return jobAlertService.get(id);
    }

    /** Replaces the name, criteria and frequency. The active flag has its own endpoint. */
    @PutMapping("/{id}")
    public JobAlertResponse update(@PathVariable UUID id, @Valid @RequestBody JobAlertRequest request) {
        return jobAlertService.update(id, request);
    }

    @PatchMapping("/{id}/status")
    public JobAlertResponse setStatus(@PathVariable UUID id, @Valid @RequestBody JobAlertStatusRequest request) {
        return jobAlertService.setActive(id, request.active());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        jobAlertService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
