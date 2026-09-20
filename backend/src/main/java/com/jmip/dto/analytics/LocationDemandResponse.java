package com.jmip.dto.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jmip.dto.LocationResponse;

/** How many postings are based in a location. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LocationDemandResponse(LocationResponse location, long jobCount) {
}
