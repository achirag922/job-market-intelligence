package com.jmip.controller;

import com.jmip.dto.resume.ResumeMatchResponse;
import com.jmip.dto.resume.ResumeResponse;
import com.jmip.dto.resume.ResumeSkillsResponse;
import com.jmip.service.resume.ResumeMatchService;
import com.jmip.service.resume.ResumeService;
import jakarta.validation.constraints.Positive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Resume upload, extraction results, and comparison against a job posting.
 */
@RestController
@RequestMapping("/api/resumes")
@Validated
public class ResumeController {

    private static final Logger log = LoggerFactory.getLogger(ResumeController.class);

    private final ResumeService resumeService;
    private final ResumeMatchService resumeMatchService;

    public ResumeController(ResumeService resumeService, ResumeMatchService resumeMatchService) {
        this.resumeService = resumeService;
        this.resumeMatchService = resumeMatchService;
    }

    /**
     * Uploads a PDF resume and processes it.
     *
     * <p>Returns 201 whenever the file was accepted and stored, including when its text
     * could not be read — the resume exists either way, and its status says which
     * happened. A rejected upload, such as the wrong file type, is a 400 and creates
     * nothing.
     */
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<ResumeResponse> upload(@RequestParam("file") MultipartFile file) {
        log.info("POST /api/resumes name={} size={}", file.getOriginalFilename(), file.getSize());
        ResumeResponse response = resumeService.upload(file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ResumeResponse> findById(@PathVariable UUID id) {
        log.info("GET /api/resumes/{}", id);
        return ResponseEntity.ok(resumeService.findById(id));
    }

    /** The extracted skills alone, for callers that already hold the metadata. */
    @GetMapping("/{id}/skills")
    public ResponseEntity<ResumeSkillsResponse> findSkills(@PathVariable UUID id) {
        log.info("GET /api/resumes/{}/skills", id);
        return ResponseEntity.ok(resumeService.findSkills(id));
    }

    /**
     * Compares the resume against one posting: matched skills, the skill gap, and the
     * skills the resume has that this job does not ask for.
     */
    @GetMapping("/{resumeId}/match/{jobId}")
    public ResponseEntity<ResumeMatchResponse> match(@PathVariable UUID resumeId,
                                                     @PathVariable @Positive Long jobId) {
        log.info("GET /api/resumes/{}/match/{}", resumeId, jobId);
        return ResponseEntity.ok(resumeMatchService.match(resumeId, jobId));
    }
}
