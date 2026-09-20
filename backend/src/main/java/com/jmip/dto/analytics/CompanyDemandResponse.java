package com.jmip.dto.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jmip.dto.CompanyResponse;

/**
 * How many postings a company has.
 *
 * @param company          the company
 * @param jobCount         RAW COUNT — postings belonging to this company
 * @param percentageOfJobs PERCENTAGE — share of all postings, to one decimal place.
 *                         Each posting has exactly one company, so these do sum to 100
 *                         across every page
 * @param rank             DERIVED — position by posting count, 1 being the most active
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompanyDemandResponse(
        CompanyResponse company,
        long jobCount,
        double percentageOfJobs,
        int rank) {
}
