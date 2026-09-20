package com.jmip.repository.projection;

/** Aggregate row behind company analytics. */
public record CompanyDemandRow(
        Long companyId, String name, String industry, String website, long jobCount) {
}
