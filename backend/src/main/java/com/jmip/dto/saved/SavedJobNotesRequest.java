package com.jmip.dto.saved;

import jakarta.validation.constraints.Size;

/** Replaces the notes; blank or absent clears them. */
public record SavedJobNotesRequest(@Size(max = 2000, message = "notes must be at most 2000 characters") String notes) {

    public SavedJobNotesRequest {
        notes = notes == null || notes.isBlank() ? null : notes.strip();
    }
}
