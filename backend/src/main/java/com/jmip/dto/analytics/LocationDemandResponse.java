package com.jmip.dto.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jmip.dto.LocationResponse;

/**
 * How many postings are based in a location.
 *
 * @param location         city, state and country as stored
 * @param jobCount         RAW COUNT — postings based here
 * @param percentageOfJobs PERCENTAGE — share of all postings, to one decimal place.
 *                         These sum to less than 100, because remote postings have no
 *                         location and are not counted anywhere in this ranking
 * @param rank             DERIVED — position by posting count, 1 being the busiest
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LocationDemandResponse(
        LocationResponse location,
        long jobCount,
        double percentageOfJobs,
        int rank) {
}
