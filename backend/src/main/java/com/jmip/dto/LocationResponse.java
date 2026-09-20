package com.jmip.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * @param displayName the parts joined for reading, e.g. "Austin, Texas, United States"
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LocationResponse(Long id, String city, String state, String country, String displayName) {
}
