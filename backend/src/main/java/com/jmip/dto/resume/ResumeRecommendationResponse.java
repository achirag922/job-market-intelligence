package com.jmip.dto.resume;

import com.jmip.dto.LocationResponse;
import com.jmip.dto.SkillResponse;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One posting recommended for a completed resume.
 *
 * <p>The percentage is the existing V3 skill-match percentage: matched job skills divided
 * by every skill the job lists. It is a transparent overlap measure, not a prediction of
 * an interview or hiring outcome.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResumeRecommendationResponse(
        Long jobId,
        String jobTitle,
        String companyName,
        LocationResponse location,
        String jobCategory,
        double matchPercentage,
        List<SkillResponse> matchedSkills,
        List<SkillResponse> missingSkills) {
}
