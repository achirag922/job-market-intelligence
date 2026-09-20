package com.jmip.dto.analytics;

import com.jmip.dto.PagedResponse;

import java.time.LocalDate;

/**
 * Skill demand, with the scope it was measured over.
 *
 * <p>The scope is part of the response on purpose. Once filters are applied, a percentage
 * on its own is unreadable: 40% of what? Returning the denominator and the filters that
 * produced it means a caller never has to guess, and a chart can label itself honestly.
 *
 * @param scope  what was measured, and over how many postings
 * @param skills the ranked skills within that scope
 */
public record SkillAnalyticsResponse(Scope scope, PagedResponse<SkillDemandResponse> skills) {

    /**
     * @param totalJobsInScope RAW COUNT — postings matching the filters, and the
     *                         denominator behind every percentage in this response
     * @param location         echo of the filter applied, null when none was
     * @param fromDate         echo of the filter applied, null when none was
     * @param toDate           echo of the filter applied, null when none was
     * @param title            echo of the filter applied, null when none was
     */
    public record Scope(
            long totalJobsInScope,
            String location,
            LocalDate fromDate,
            LocalDate toDate,
            String title) {
    }
}
