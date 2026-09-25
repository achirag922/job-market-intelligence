package com.jmip.dto.resume;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jmip.dto.SkillResponse;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * An uploaded resume as the API exposes it.
 *
 * <p>Deliberately omits the stored filename, the storage directory and the extracted
 * text. The first two are internal filesystem detail no client should learn, and the
 * third is the whole document: large, unnecessary for any screen, and the most sensitive
 * thing held about the user.
 *
 * @param id             identifies the resume in later calls
 * @param fileName       the name the user uploaded, shown back to them
 * @param fileSizeBytes  size as uploaded
 * @param status         UPLOADED, PROCESSING, COMPLETED or FAILED
 * @param skills         skills found in the document; empty until processing completes
 * @param errorMessage   why processing failed, present only when the status is FAILED
 * @param title          V7.3: the owner's name for this version; starts as the file name
 * @param versionLabel   V7.3: optional short label, e.g. "v2"
 * @param isDefault      V7.3: whether this is the account's default resume
 * @param updatedAt      V7.3: when the resume or its metadata last changed
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResumeResponse(
        UUID id,
        String fileName,
        long fileSizeBytes,
        String status,
        List<SkillResponse> skills,
        String errorMessage,
        OffsetDateTime uploadedAt,
        OffsetDateTime processedAt,
        String title,
        String versionLabel,
        boolean isDefault,
        OffsetDateTime updatedAt) {
}
