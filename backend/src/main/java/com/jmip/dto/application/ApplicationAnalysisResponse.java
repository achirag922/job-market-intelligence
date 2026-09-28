package com.jmip.dto.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jmip.dto.JobSummaryResponse;
import com.jmip.dto.SkillResponse;
import com.jmip.entity.ApplicationStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * V8.5: one tracked job with where the application stands and how the user's current resume
 * matches it (the V8.3 score). Match fields are absent without a processed resume, and
 * {@code matchNote} then says why.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplicationAnalysisResponse(
        UUID id,
        JobSummaryResponse job,
        ApplicationStatus status,
        OffsetDateTime savedAt,
        OffsetDateTime appliedAt,
        String notes,
        LocalDate followUpOn,
        String followUpNote,
        Double overallMatchPercentage,
        Double skillMatchPercentage,
        List<SkillResponse> matchedSkills,
        List<SkillResponse> missingSkills,
        String matchNote) {
}
