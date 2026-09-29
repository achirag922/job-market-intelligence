package com.jmip.dto.resume;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * V8.3: the signed-in user's job-match preferences. Every field is optional; an empty one
 * leaves its dimension of the match breakdown unavailable.
 *
 * <p>V9.2 adds the personalization lists: preferred roles (job categories) and skills, which the
 * personalized feed rewards and explains, and companies and locations to leave out of it.
 *
 * @param preferredLocation "City", "City, Country" or just "Country"
 * @param workMode          REMOTE, HYBRID or ON_SITE; empty for no preference
 * @param minSalary         compared only against postings in {@code salaryCurrency}
 */
public record MatchPreferences(
        @Min(0) @Max(60) Integer yearsExperience,
        @Size(max = 200) String preferredLocation,
        @Pattern(regexp = "REMOTE|HYBRID|ON_SITE", message = "must be REMOTE, HYBRID or ON_SITE") String workMode,
        @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal minSalary,
        @Pattern(regexp = "[A-Z]{3}", message = "must be a three letter ISO 4217 code") String salaryCurrency,
        @Size(max = 20, message = "at most 20 preferred roles") List<@Size(max = 100) String> preferredCategories,
        @Size(max = 20, message = "at most 20 preferred skills") List<@Size(max = 100) String> preferredSkills,
        @Size(max = 20, message = "at most 20 excluded companies") List<@Size(max = 255) String> excludedCompanies,
        @Size(max = 20, message = "at most 20 excluded locations") List<@Size(max = 200) String> excludedLocations) {

    public static final MatchPreferences NONE = new MatchPreferences(null, null, null, null, null);

    /** The V8.3 shape, without the V9.2 lists. */
    public MatchPreferences(Integer yearsExperience, String preferredLocation, String workMode, BigDecimal minSalary,
                            String salaryCurrency) {
        this(yearsExperience, preferredLocation, workMode, minSalary, salaryCurrency, null, null, null, null);
    }

    /** Blank strings are "not set", so the form can send what the user cleared; lists drop blanks and repeats. */
    public MatchPreferences {
        preferredLocation = blankToNull(preferredLocation);
        workMode = blankToNull(workMode);
        salaryCurrency = blankToNull(salaryCurrency);
        preferredCategories = clean(preferredCategories);
        preferredSkills = clean(preferredSkills);
        excludedCompanies = clean(excludedCompanies);
        excludedLocations = clean(excludedLocations);
    }

    @JsonIgnore
    @AssertTrue(message = "a minimum salary needs a currency, and a currency needs a minimum salary")
    public boolean isSalaryComplete() {
        return (minSalary == null) == (salaryCurrency == null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static List<String> clean(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(values.stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isEmpty()).toList()));
    }
}
