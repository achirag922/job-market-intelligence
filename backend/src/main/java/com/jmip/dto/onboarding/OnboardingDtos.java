package com.jmip.dto.onboarding;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** V9.12: onboarding requests and responses. The owner always comes from the session. */
public final class OnboardingDtos {

    private OnboardingDtos() {
    }

    /**
     * The career profile step. Experience and skills are saved to the existing match preferences;
     * the target role is kept for the career-goal step.
     */
    public record ProfileRequest(
            @NotBlank(message = "enter the role you are aiming for") @Size(max = 100) String targetRole,
            @NotNull(message = "enter your years of experience") @Min(0) @Max(60) Integer yearsExperience,
            @NotEmpty(message = "add at least one skill or interest")
            @Size(max = 20, message = "at most 20 skills") List<@NotBlank @Size(max = 100) String> skills) {
    }

    /** The job preferences step, saved to the existing match preferences. At least one field is required. */
    public record PreferencesRequest(
            @Size(max = 200) String preferredLocation,
            @Pattern(regexp = "REMOTE|HYBRID|ON_SITE", message = "must be REMOTE, HYBRID or ON_SITE") String workMode,
            @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal minSalary,
            @Pattern(regexp = "[A-Z]{3}", message = "must be a three letter ISO 4217 code") String salaryCurrency,
            @Size(max = 20, message = "at most 20 job categories") List<@NotBlank @Size(max = 100) String> preferredCategories) {
    }

    /** Which steps are done: profile and preferences when saved here, resume and goal from the existing data. */
    public record Steps(boolean profile, boolean resume, boolean preferences, boolean careerGoal) {
    }

    /** What the profile step already holds, to prefill it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProfileDraft(String targetRole, Integer yearsExperience, List<String> skills) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PreferencesDraft(String preferredLocation, String workMode, BigDecimal minSalary, String salaryCurrency,
                                   List<String> preferredCategories) {
    }

    /**
     * @param status   PENDING (not finished or skipped yet), SKIPPED or COMPLETED
     * @param nextStep PROFILE, RESUME, PREFERENCES, CAREER_GOAL or DONE: the first step not done
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Status(String status, Steps steps, int completedSteps, int totalSteps, String nextStep,
                         ProfileDraft profile, PreferencesDraft preferences, OffsetDateTime completedAt,
                         OffsetDateTime skippedAt) {
    }
}
