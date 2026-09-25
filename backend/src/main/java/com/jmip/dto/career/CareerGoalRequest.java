package com.jmip.dto.career;

import com.jmip.entity.CareerGoal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Locale;

/**
 * V7.4: create or replace a career goal. The category must be one of the V4 job categories;
 * target skills must be skills JMIP already knows. There is no owner field: the owner is
 * always the signed-in account.
 */
public record CareerGoalRequest(
        @NotBlank(message = "targetRole is required")
        @Size(max = 100, message = "targetRole must be at most 100 characters")
        String targetRole,

        @NotBlank(message = "targetCategory is required (a job category, e.g. Backend Developer)")
        @Size(max = 50, message = "targetCategory must be at most 50 characters")
        String targetCategory,

        @Size(max = 200, message = "targetLocation must be at most 200 characters")
        String targetLocation,

        @Pattern(regexp = "0-2|2-5|5-8|8\\+", message = "targetExperience must be one of 0-2, 2-5, 5-8, 8+")
        String targetExperience,

        @Size(max = 20, message = "at most 20 target skills")
        List<@NotBlank(message = "a target skill cannot be blank") @Size(max = 100) String> targetSkills) {

    public CareerGoalRequest {
        targetRole = strip(targetRole);
        targetCategory = strip(targetCategory);
        targetLocation = blankToNull(targetLocation);
        targetExperience = blankToNull(targetExperience);
        targetSkills = targetSkills == null ? List.of()
                : targetSkills.stream().map(skill -> skill == null ? null : skill.strip()).toList();
    }

    public CareerGoal.Details details() {
        return new CareerGoal.Details(targetRole, targetCategory, targetLocation,
                targetExperience == null ? null : targetExperience.toLowerCase(Locale.ROOT));
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
