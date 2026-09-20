package com.jmip.dto.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * How many postings share a role, after similar titles have been grouped.
 *
 * @param title              DERIVED — the normalised title that groups these postings.
 *                           No posting necessarily carries this exact string
 * @param variants           RAW — the distinct stored titles folded into this group, so
 *                           the grouping can always be audited rather than trusted
 * @param jobCount           RAW COUNT — postings in the group
 * @param percentageOfJobs   PERCENTAGE — share of all postings, to one decimal place
 * @param rank               DERIVED — position by posting count, 1 being most common
 * @param topSkills          the skills these postings ask for most
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TitleDemandResponse(
        String title,
        List<String> variants,
        long jobCount,
        double percentageOfJobs,
        int rank,
        List<TitleSkill> topSkills) {

    /**
     * @param skill                   canonical skill name
     * @param jobCount                RAW COUNT — postings in this title group asking for it
     * @param percentageOfTitleJobs   PERCENTAGE — share of the group, not of all postings
     */
    public record TitleSkill(Long skillId, String skill, String category,
                             long jobCount, double percentageOfTitleJobs) {
    }
}
