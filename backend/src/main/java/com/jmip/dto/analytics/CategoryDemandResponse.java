package com.jmip.dto.analytics;

/**
 * How many postings fall into a job category.
 *
 * @param category         the rule-based category, or "Other" when nothing matched
 * @param jobCount         RAW COUNT — postings in this category
 * @param percentageOfJobs PERCENTAGE — share of all classified postings, to one decimal
 *                         place. Each posting has exactly one category, so these sum to
 *                         100 across every page
 * @param rank             DERIVED — position by posting count, 1 being the most common
 */
public record CategoryDemandResponse(String category, long jobCount, double percentageOfJobs, int rank) {
}
