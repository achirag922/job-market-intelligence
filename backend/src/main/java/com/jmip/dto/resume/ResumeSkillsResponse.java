package com.jmip.dto.resume;

import com.jmip.dto.SkillResponse;

import java.util.List;
import java.util.UUID;

/**
 * Just the skills, for callers that already have the resume metadata.
 *
 * @param skillCount RAW COUNT — how many distinct skills were found
 */
public record ResumeSkillsResponse(UUID resumeId, int skillCount, List<SkillResponse> skills) {
}
