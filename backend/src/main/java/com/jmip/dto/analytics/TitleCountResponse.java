package com.jmip.dto.analytics;

/**
 * A normalised job title and how often it appears somewhere specific, such as one city.
 *
 * @param title              DERIVED — normalised grouping title
 * @param jobCount           RAW COUNT — postings in that place with this title
 * @param percentageOfJobs   PERCENTAGE — share of that place's own postings
 */
public record TitleCountResponse(String title, long jobCount, double percentageOfJobs) {
}
