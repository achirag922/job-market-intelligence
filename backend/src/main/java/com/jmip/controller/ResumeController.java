package com.jmip.controller;

import com.jmip.dto.resume.CareerInsightsResponse;
import com.jmip.dto.resume.ResumeMatchResponse;
import com.jmip.dto.resume.ResumeRecommendationResponse;
import com.jmip.dto.resume.ResumeResponse;
import com.jmip.dto.resume.ResumeSkillsResponse;
import com.jmip.service.resume.CareerInsightsService;
import com.jmip.service.resume.ResumeMatchService;
import com.jmip.service.resume.ResumeService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
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
import java.util.List;

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
    private final CareerInsightsService careerInsightsService;

    public ResumeController(ResumeService resumeService, ResumeMatchService resumeMatchService,
                            CareerInsightsService careerInsightsService) {
        this.resumeService = resumeService;
        this.resumeMatchService = resumeMatchService;
        this.careerInsightsService = careerInsightsService;
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

    /**
     * The resume's highest matching postings, using the same deterministic V3 score as
     * {@link #match(UUID, Long)}. No job with no skills or no overlap is returned.
     */
    @GetMapping("/{resumeId}/recommendations")
    public ResponseEntity<List<ResumeRecommendationResponse>> recommendations(
            @PathVariable UUID resumeId,
            @RequestParam(defaultValue = "10") @Min(1)
            @Max(20) int limit) {
        log.info("GET /api/resumes/{}/recommendations limit={}", resumeId, limit);
        return ResponseEntity.ok(resumeMatchService.recommend(resumeId, limit));
    }

    /**
     * The resume set against one job category's skill demand and trends, with the V6.3
     * recommendations that fall in that category. Without a category, the category of the
     * best recommendation is used. {@code summary=true} adds a V5 AI description of the
     * same figures.
     */
    @GetMapping("/{resumeId}/career-insights")
    public ResponseEntity<CareerInsightsResponse> careerInsights(
            @PathVariable UUID resumeId,
            @RequestParam(required = false) @Size(max = 50) String category,
            @RequestParam(defaultValue = "false") boolean summary) {
        log.info("GET /api/resumes/{}/career-insights category={} summary={}", resumeId, category, summary);
        return ResponseEntity.ok(careerInsightsService.insights(resumeId, category, summary));
    }
}
