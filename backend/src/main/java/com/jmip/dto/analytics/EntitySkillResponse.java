package com.jmip.dto.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A skill in demand within one company or one location.
 *
 * @param skillId             identifier
 * @param skill               canonical skill name
 * @param category            skill category, null if the dictionary gave none
 * @param jobCount            RAW COUNT — postings here that ask for this skill
 * @param percentageOfJobs    PERCENTAGE — share of that company's or location's own
 *                            postings, never of all postings. A skill on every one of a
 *                            small company's three jobs is 100% of them
 * @param rank                DERIVED — position within this company or location
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EntitySkillResponse(
        Long skillId,
        String skill,
        String category,
        long jobCount,
        double percentageOfJobs,
        int rank) {
}
