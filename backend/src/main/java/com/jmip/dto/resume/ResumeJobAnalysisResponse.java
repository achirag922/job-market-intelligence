package com.jmip.dto.resume;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jmip.dto.ExperienceResponse;
import com.jmip.dto.SkillResponse;

import java.util.List;
import java.util.UUID;

/**
 * V7.3: one resume against one job. The percentage and skill lists are the V3 match,
 * unchanged; the suggestions are derived only from those lists and the posting's stated
 * experience. Nothing here predicts a hiring outcome.
 *
 * @param matchedSkills     resume skills the job asks for (the resume's skills relevant to the job)
 * @param missingSkills     job skills not found in the resume
 * @param otherResumeSkills resume skills this posting does not list
 * @param experience        what the posting states; resumes carry no extracted experience
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResumeJobAnalysisResponse(
        UUID resumeId,
        String resumeTitle,
        String resumeVersionLabel,
        Long jobId,
        String jobTitle,
        String companyName,
        String jobCategory,
        Double matchPercentage,
        String matchNote,
        int totalJobSkills,
        int matchedSkillCount,
        int missingSkillCount,
        List<SkillResponse> matchedSkills,
        List<SkillResponse> missingSkills,
        List<SkillResponse> otherResumeSkills,
        Experience experience,
        List<String> suggestions,
        String disclaimer) {

    /** @param required the posting's range, absent when it states none */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Experience(ExperienceResponse required, String note) {
    }
}
