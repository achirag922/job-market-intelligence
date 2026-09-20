package com.jmip.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompanyResponse(Long id, String name, String industry, String website) {
}
