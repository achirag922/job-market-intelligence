package com.jmip.controller;

import com.jmip.dto.resume.ResumeComparisonResponse;
import com.jmip.dto.resume.ResumeJobAnalysisResponse;
import com.jmip.dto.resume.ResumeMetadataRequest;
import com.jmip.dto.resume.ResumeResponse;
import com.jmip.service.resume.ResumeAnalysisService;
import com.jmip.service.resume.ResumeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * V7.3: resume versions (list, rename, default), job-specific analysis and version
 * comparison. Upload, fetch, delete and the V3 match stay in {@link ResumeController}.
 * Every resume id is checked against the signed-in account by {@link ResumeService}.
 */
@RestController
@RequestMapping("/api/resumes")
@Validated
public class ResumeVersionController {

    private final ResumeService resumeService;
    private final ResumeAnalysisService analysisService;
    private final com.jmip.service.resume.ResumeOptimizationService optimizationService;

    public ResumeVersionController(ResumeService resumeService, ResumeAnalysisService analysisService,
                                   com.jmip.service.resume.ResumeOptimizationService optimizationService) {
        this.resumeService = resumeService;
        this.analysisService = analysisService;
        this.optimizationService = optimizationService;
    }

    /** The caller's resumes, newest first. */
    @GetMapping
    public List<ResumeResponse> list() {
        return resumeService.list();
    }

    /** Rename or relabel a resume. */
    @PatchMapping("/{id}")
    public ResumeResponse describe(@PathVariable UUID id, @Valid @RequestBody ResumeMetadataRequest request) {
        return resumeService.describe(id, request.title(), request.versionLabel());
    }

    /** Make this the account's default resume. */
    @PutMapping("/{id}/default")
    public ResumeResponse makeDefault(@PathVariable UUID id) {
        return resumeService.makeDefault(id);
    }

    @GetMapping("/{resumeId}/analyze-job/{jobId}")
    public ResumeJobAnalysisResponse analyzeJob(@PathVariable UUID resumeId, @PathVariable @Positive Long jobId) {
        return analysisService.analyzeJob(resumeId, jobId);
    }

    /** Skills added and removed from the first resume to the second, and what they share. */
    @GetMapping("/compare")
    public ResumeComparisonResponse compare(@RequestParam UUID resumeId1, @RequestParam UUID resumeId2) {
        return analysisService.compare(resumeId1, resumeId2);
    }

    /** V8.6: match, skills, keywords, sections and suggestions for one of your resumes against one job. */
    @GetMapping("/{resumeId}/optimize/{jobId}")
    public com.jmip.dto.resume.ResumeOptimizationResponse optimize(@PathVariable UUID resumeId,
                                                                   @PathVariable @Positive Long jobId) {
        return optimizationService.optimize(resumeId, jobId);
    }

    /** V8.6: two of your resume versions against one job: skill changes, match change, terms gained and lost. */
    @GetMapping("/compare-for-job")
    public com.jmip.dto.resume.ResumeJobComparisonResponse compareForJob(@RequestParam UUID resumeId1,
                                                                         @RequestParam UUID resumeId2,
                                                                         @RequestParam @Positive Long jobId) {
        return optimizationService.compareForJob(resumeId1, resumeId2, jobId);
    }
}
