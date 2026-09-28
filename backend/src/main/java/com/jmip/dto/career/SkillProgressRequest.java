package com.jmip.dto.career;

import com.jmip.entity.SkillProgressStatus;
import jakarta.validation.constraints.NotNull;

public record SkillProgressRequest(
        @NotNull(message = "status is required (NOT_STARTED, IN_PROGRESS or COMPLETED)") SkillProgressStatus status) {
}
