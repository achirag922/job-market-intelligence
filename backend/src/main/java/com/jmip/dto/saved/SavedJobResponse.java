package com.jmip.dto.saved;

import com.jmip.dto.JobSummaryResponse;
import com.jmip.entity.ApplicationStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A saved job as its owner sees it. The owning account is implied, never echoed back. */
public record SavedJobResponse(
        UUID id,
        JobSummaryResponse job,
        ApplicationStatus status,
        String notes,
        OffsetDateTime savedAt,
        OffsetDateTime appliedAt,
        OffsetDateTime updatedAt) {
}
