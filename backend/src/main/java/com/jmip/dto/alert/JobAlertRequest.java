package com.jmip.dto.alert;

import com.jmip.entity.AlertFrequency;
import com.jmip.entity.JobAlert;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Locale;
import java.util.stream.Stream;

/**
 * Create or replace a job alert. The filters, and their limits, are the job search's own.
 * There is deliberately no owner field: the owner is always the signed-in account.
 */
public record JobAlertRequest(
        @NotBlank(message = "name is required")
        @Size(max = 100, message = "name must be at most 100 characters")
        String name,

        @Size(max = 200, message = "keywords must be at most 200 characters")
        String keywords,

        @Size(max = 50, message = "category must be at most 50 characters")
        String category,

        @Size(max = 200, message = "location must be at most 200 characters")
        String location,

        @Pattern(regexp = "(?i)0-2|2-5|5-8|8\\+|unspecified",
                message = "experience must be one of 0-2, 2-5, 5-8, 8+, unspecified")
        String experience,

        @Size(max = 100, message = "skill must be at most 100 characters")
        String skill,

        @NotNull(message = "frequency is required (DAILY or WEEKLY)")
        AlertFrequency frequency) {

    public JobAlertRequest {
        name = blankToNull(name);
        keywords = blankToNull(keywords);
        category = blankToNull(category);
        location = blankToNull(location);
        experience = experience == null || experience.isBlank() ? null : experience.strip().toLowerCase(Locale.ROOT);
        skill = blankToNull(skill);
    }

    @AssertTrue(message = "give at least one of keywords, category, location, experience or skill")
    public boolean isCriteriaGiven() {
        return Stream.of(keywords, category, location, experience, skill).anyMatch(value -> value != null);
    }

    public JobAlert.Criteria toCriteria() {
        return new JobAlert.Criteria(name, keywords, category, location, experience, skill, frequency);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
