package com.jmip.dto.resume;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * V8.3: the signed-in user's job-match preferences. Every field is optional; an empty one
 * leaves its dimension of the match breakdown unavailable.
 *
 * @param preferredLocation "City", "City, Country" or just "Country"
 * @param workMode          REMOTE, HYBRID or ON_SITE; empty for no preference
 * @param minSalary         compared only against postings in {@code salaryCurrency}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MatchPreferences(
        @Min(0) @Max(60) Integer yearsExperience,
        @Size(max = 200) String preferredLocation,
        @Pattern(regexp = "REMOTE|HYBRID|ON_SITE", message = "must be REMOTE, HYBRID or ON_SITE") String workMode,
        @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal minSalary,
        @Pattern(regexp = "[A-Z]{3}", message = "must be a three letter ISO 4217 code") String salaryCurrency) {

    public static final MatchPreferences NONE = new MatchPreferences(null, null, null, null, null);

    /** Blank strings are "not set", so the form can send what the user cleared. */
    public MatchPreferences {
        preferredLocation = blankToNull(preferredLocation);
        workMode = blankToNull(workMode);
        salaryCurrency = blankToNull(salaryCurrency);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    @AssertTrue(message = "a minimum salary needs a currency, and a currency needs a minimum salary")
    public boolean isSalaryComplete() {
        return (minSalary == null) == (salaryCurrency == null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
