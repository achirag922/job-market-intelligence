package com.jmip.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * The filters accepted by job search. Every one is optional; supplying none returns
 * everything.
 *
 * <p>Bound from query parameters, so this is a request DTO rather than a response one.
 *
 * @param q              free text matched across title, company, location, description and
 *                       skills. Multiple words all have to match, each against any of
 *                       those fields
 * @param title          case-insensitive substring of the posting title
 * @param location       matched against city, state or country
 * @param company        case-insensitive substring of the company name
 * @param skill          exact skill name, case-insensitive
 * @param employmentType one of the six values the schema allows
 * @param category       exact V4 job category, case-insensitive
 * @param experience     an experience band slug — {@code 0-2}, {@code 2-5}, {@code 5-8},
 *                       {@code 8+} or {@code unspecified}
 * @param salaryMin      lowest stated minimum to accept, in {@code currency}
 * @param salaryMax      highest stated maximum to accept, in {@code currency}
 * @param currency       ISO code scoping the salary bounds. Required with them: the
 *                       dataset holds eight currencies and no exchange rates, so a bare
 *                       number would compare rupees against dollars
 * @param locationStated true for postings that name a place, false for those that do not.
 *                       Not a remote filter — see {@link #locationStated()}
 */
public record JobSearchCriteria(
        @Size(max = 200, message = "search text must be at most 200 characters")
        String q,

        @Size(max = 300, message = "title filter must be at most 300 characters")
        String title,

        @Size(max = 200, message = "location filter must be at most 200 characters")
        String location,

        @Size(max = 255, message = "company filter must be at most 255 characters")
        String company,

        @Size(max = 100, message = "skill filter must be at most 100 characters")
        String skill,

        @Pattern(
                regexp = "(?i)FULL_TIME|PART_TIME|CONTRACT|INTERNSHIP|TEMPORARY|FREELANCE",
                message = "employmentType must be one of FULL_TIME, PART_TIME, CONTRACT, "
                        + "INTERNSHIP, TEMPORARY, FREELANCE")
        String employmentType,

        @Size(max = 50, message = "category filter must be at most 50 characters")
        String category,

        @Pattern(
                regexp = "(?i)0-2|2-5|5-8|8\\+|unspecified",
                message = "experience must be one of 0-2, 2-5, 5-8, 8+, unspecified")
        String experience,

        @DecimalMin(value = "0", message = "salaryMin cannot be negative")
        @Digits(integer = 12, fraction = 2, message = "salaryMin is not a valid amount")
        BigDecimal salaryMin,

        @DecimalMin(value = "0", message = "salaryMax cannot be negative")
        @Digits(integer = 12, fraction = 2, message = "salaryMax is not a valid amount")
        BigDecimal salaryMax,

        @Pattern(regexp = "(?i)[A-Z]{3}", message = "currency must be a three-letter ISO code")
        String currency,

        Boolean locationStated) {

    /**
     * The filters as they were before V6.2.
     *
     * <p>Kept so callers that only ever set these — the assistant's job search among them
     * — do not have to name six nulls they have no opinion about.
     */
    public JobSearchCriteria(String title, String location, String company, String skill,
                             String employmentType, String category) {
        this(null, title, location, company, skill, employmentType, category,
                null, null, null, null, null);
    }

    /** Blank query parameters are treated as absent, so "?title=" does not filter on "". */
    public JobSearchCriteria {
        q = blankToNull(q);
        title = blankToNull(title);
        location = blankToNull(location);
        company = blankToNull(company);
        skill = blankToNull(skill);
        employmentType = blankToNull(employmentType);
        category = blankToNull(category);
        experience = blankToNull(experience);
        currency = blankToNull(currency);
    }

    public boolean hasQuery() {
        return q != null;
    }

    public boolean hasTitle() {
        return title != null;
    }

    public boolean hasLocationFilter() {
        return location != null;
    }

    public boolean hasCompany() {
        return company != null;
    }

    public boolean hasSkill() {
        return skill != null;
    }

    public boolean hasEmploymentType() {
        return employmentType != null;
    }

    public boolean hasCategory() {
        return category != null;
    }

    public boolean hasExperience() {
        return experience != null;
    }

    /**
     * Whether a salary bound was given <em>and</em> can be applied.
     *
     * <p>Both halves matter. A bound without a currency is not a filter anyone can mean:
     * "at least 100,000" picks out most rupee salaries and almost no dollar ones, so
     * applying it unscoped would silently return the wrong postings. The service rejects
     * that combination outright rather than guess which currency was meant.
     */
    public boolean hasSalaryFilter() {
        return currency != null && (salaryMin != null || salaryMax != null);
    }

    /** A salary bound was supplied that cannot be honoured, because no currency scopes it. */
    public boolean hasUnscopedSalaryFilter() {
        return currency == null && (salaryMin != null || salaryMax != null);
    }

    /**
     * Whether the posting names a place.
     *
     * <p>Deliberately not called "remote". The ETL maps "remote", "work from home" and
     * "unspecified" all to no location, so a posting without one may be either — the
     * dataset cannot tell them apart and neither can this filter.
     */
    public Boolean locationStated() {
        return locationStated;
    }

    public boolean hasLocationPresenceFilter() {
        return locationStated != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
