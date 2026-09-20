package com.jmip.dto.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jmip.dto.CompanyResponse;

/** How many postings a company has. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompanyDemandResponse(CompanyResponse company, long jobCount) {
}
