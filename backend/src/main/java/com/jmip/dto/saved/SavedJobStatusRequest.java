package com.jmip.dto.saved;

import com.jmip.entity.ApplicationStatus;
import jakarta.validation.constraints.NotNull;

public record SavedJobStatusRequest(
        @NotNull(message = "status is required (SAVED, APPLIED, INTERVIEW, OFFER, REJECTED or WITHDRAWN)")
        ApplicationStatus status) {
}
