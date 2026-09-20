package com.jmip.dto.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * How required experience is distributed across all postings.
 *
 * @param totalJobs RAW COUNT — every posting, and the denominator for the percentages.
 *                  The bands sum to exactly this, because postings that state no
 *                  requirement are reported as their own band rather than dropped
 * @param buckets   the bands, in increasing order of experience
 */
public record ExperienceDistributionResponse(long totalJobs, List<Bucket> buckets) {

    /**
     * @param bucket           stable identifier, e.g. {@code TWO_TO_FIVE}
     * @param label            human readable band, e.g. "2–5 years"
     * @param minYears         inclusive lower bound, null for the unspecified band
     * @param maxYearsExclusive exclusive upper bound, null for "8+" and unspecified
     * @param jobCount         RAW COUNT — postings whose minimum requirement falls here
     * @param percentageOfJobs PERCENTAGE — share of all postings, to one decimal place
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Bucket(
            String bucket,
            String label,
            Integer minYears,
            Integer maxYearsExclusive,
            long jobCount,
            double percentageOfJobs) {
    }
}
