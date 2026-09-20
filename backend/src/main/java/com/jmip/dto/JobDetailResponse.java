package com.jmip.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A single posting in full, including the description and where it was ingested from.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobDetailResponse(
        Long id,
        String title,
        CompanyResponse company,
        LocationResponse location,
        String description,
        String employmentType,
        ExperienceResponse experience,
        SalaryResponse salary,
        LocalDate postedDate,
        String source,
        String sourceUrl,
        List<SkillResponse> skills,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
