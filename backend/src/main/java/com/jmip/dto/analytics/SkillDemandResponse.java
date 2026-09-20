package com.jmip.dto.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * How many postings ask for a skill.
 *
 * <p>The three measures are deliberately distinct, and each is labelled below:
 *
 * <ul>
 *   <li><b>Raw count</b> — counted directly from stored rows.</li>
 *   <li><b>Percentage</b> — that count over the total in scope.</li>
 *   <li><b>Derived</b> — computed from the ordering, not stored anywhere.</li>
 * </ul>
 *
 * @param skillId          identifier
 * @param skill            canonical skill name
 * @param category         skill category, null if the dictionary gave none
 * @param jobCount         RAW COUNT — postings in scope mentioning this skill
 * @param percentageOfJobs PERCENTAGE — {@code jobCount} as a share of the postings in
 *                         scope, to one decimal place. Postings need several skills, so
 *                         these do not sum to 100
 * @param rank             DERIVED — position in the ranking, 1 being most in demand.
 *                         Ordinal, so equal counts still take consecutive positions
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SkillDemandResponse(
        Long skillId,
        String skill,
        String category,
        long jobCount,
        double percentageOfJobs,
        int rank) {
}
