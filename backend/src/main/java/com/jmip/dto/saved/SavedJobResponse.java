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
        OffsetDateTime updatedAt,
        /** V8.5: the user's follow-up date and reminder; absent when none is set. */
        java.time.LocalDate followUpOn,
        String followUpNote,
        /** V9.14: HIGH, MEDIUM or LOW; absent when not set. */
        String priority) {

    /** The V7.2 shape, without a follow-up. */
    public SavedJobResponse(UUID id, JobSummaryResponse job, ApplicationStatus status, String notes,
                            OffsetDateTime savedAt, OffsetDateTime appliedAt, OffsetDateTime updatedAt) {
        this(id, job, status, notes, savedAt, appliedAt, updatedAt, null, null, null);
    }
}
