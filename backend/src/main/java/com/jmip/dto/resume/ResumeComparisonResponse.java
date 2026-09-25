package com.jmip.dto.resume;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jmip.dto.SkillResponse;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * V7.3: two of the caller's resumes side by side, from the skills already extracted at
 * upload. "Added" and "removed" read from the first resume to the second.
 *
 * @param differentFields names of the {@link Version} fields whose values differ
 */
public record ResumeComparisonResponse(
        Version first,
        Version second,
        List<SkillResponse> skillsAdded,
        List<SkillResponse> skillsRemoved,
        List<SkillResponse> commonSkills,
        List<String> differentFields) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Version(
            UUID id,
            String title,
            String versionLabel,
            String fileName,
            boolean isDefault,
            int skillCount,
            OffsetDateTime uploadedAt,
            OffsetDateTime updatedAt) {
    }
}
