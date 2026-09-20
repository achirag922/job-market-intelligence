package com.jmip.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The filters accepted by job search. Every one is optional; supplying none returns
 * everything.
 *
 * <p>Bound from query parameters, so this is a request DTO rather than a response one.
 *
 * @param title          case-insensitive substring of the posting title
 * @param location       matched against city, state or country
 * @param company        case-insensitive substring of the company name
 * @param skill          exact skill name, case-insensitive
 * @param employmentType one of the six values the schema allows
 */
public record JobSearchCriteria(
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
        String employmentType) {

    /** Blank query parameters are treated as absent, so "?title=" does not filter on "". */
    public JobSearchCriteria {
        title = blankToNull(title);
        location = blankToNull(location);
        company = blankToNull(company);
        skill = blankToNull(skill);
        employmentType = blankToNull(employmentType);
    }

    public boolean hasTitle() {
        return title != null;
    }

    public boolean hasLocation() {
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
