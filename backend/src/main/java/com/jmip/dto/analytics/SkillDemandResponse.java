package com.jmip.dto.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * How many postings ask for a skill.
 *
 * @param jobCount          postings mentioning this skill
 * @param percentageOfJobs  that count as a share of all postings, rounded to one decimal
 *                          place. A posting usually needs several skills, so these do not
 *                          sum to 100.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SkillDemandResponse(
        Long skillId,
        String skill,
        String category,
        long jobCount,
        double percentageOfJobs) {
}
