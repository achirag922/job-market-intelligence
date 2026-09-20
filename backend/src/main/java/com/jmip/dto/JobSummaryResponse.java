package com.jmip.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * A posting as it appears in a list. Deliberately excludes the description, which is long
 * and never read in a list view.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobSummaryResponse(
        Long id,
        String title,
        CompanyResponse company,
        LocationResponse location,
        String employmentType,
        ExperienceResponse experience,
        SalaryResponse salary,
        LocalDate postedDate,
        /** V4: the rule-based category, absent until the posting has been classified. */
        String category,
        List<SkillResponse> skills) {
}
