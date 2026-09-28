package com.jmip.dto.saved;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** V8.5: sets the follow-up date and reminder; no date clears both. */
public record SavedJobFollowUpRequest(
        LocalDate followUpOn,
        @Size(max = 200, message = "the reminder must be at most 200 characters") String note) {

    public SavedJobFollowUpRequest {
        note = note == null || note.isBlank() ? null : note.strip();
    }
}
