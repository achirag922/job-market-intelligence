package com.jmip.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A company plus the figure a caller almost always wants next: how many postings it has.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompanyDetailResponse(
        Long id, String name, String industry, String website, long jobCount) {
}
