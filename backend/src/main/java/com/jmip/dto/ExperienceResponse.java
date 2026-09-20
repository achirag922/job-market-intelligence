package com.jmip.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Years of experience required. Either bound may be absent. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExperienceResponse(Integer min, Integer max) {

    public static ExperienceResponse of(Short min, Short max) {
        if (min == null && max == null) {
            return null;
        }
        return new ExperienceResponse(
                min == null ? null : min.intValue(),
                max == null ? null : max.intValue());
    }
}
