package com.jmip.repository.projection;

/** Aggregate row behind location analytics. */
public record LocationDemandRow(
        Long locationId, String city, String state, String country, long jobCount) {
}
