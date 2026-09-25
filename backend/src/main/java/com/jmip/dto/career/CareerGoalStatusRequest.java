package com.jmip.dto.career;

import com.jmip.entity.CareerGoalStatus;
import jakarta.validation.constraints.NotNull;

public record CareerGoalStatusRequest(
        @NotNull(message = "status is required (ACTIVE, COMPLETED or ARCHIVED)") CareerGoalStatus status) {
}
