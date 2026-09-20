package com.jmip.dto.analytics;

/**
 * Headline counts for the dashboard.
 */
public record OverviewResponse(
        long totalJobs,
        long totalCompanies,
        long totalSkills,
        long totalLocations) {
}
