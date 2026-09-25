package com.jmip.dto.career;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jmip.dto.SkillResponse;
import com.jmip.entity.CareerGoalStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** A career goal as its owner sees it. The owning account is implied, never echoed back. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CareerGoalResponse(
        UUID id,
        String targetRole,
        String targetCategory,
        String targetLocation,
        String targetExperience,
        List<SkillResponse> targetSkills,
        CareerGoalStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
