package com.jmip.dto.resume;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** V7.3: rename or relabel a resume. A blank version label clears it. */
public record ResumeMetadataRequest(
        @NotBlank(message = "title is required")
        @Size(max = 100, message = "title must be at most 100 characters")
        String title,

        @Size(max = 50, message = "versionLabel must be at most 50 characters")
        String versionLabel) {

    public ResumeMetadataRequest {
        title = title == null ? null : title.strip();
        versionLabel = versionLabel == null || versionLabel.isBlank() ? null : versionLabel.strip();
    }
}
