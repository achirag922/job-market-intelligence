package com.jmip.dto.resume;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jmip.dto.SkillResponse;

import java.util.List;
import java.util.UUID;

/**
 * How one resume compares against one job posting.
 *
 * <p>Purely a comparison of extracted skill sets. It says nothing about experience,
 * seniority, salary expectations or anything else a hiring decision turns on, and the
 * percentage is <em>not</em> a probability of being hired — it is the share of the job's
 * listed skills that appear in the resume, and nothing more.
 *
 * @param matchPercentage  DERIVED — matched skills over required skills, to one decimal
 *                         place. Null when the job lists no skills at all, because there
 *                         is then nothing to be a share of; see {@code matchNote}
 * @param matchNote        present only when a percentage could not be computed, saying why
 * @param totalJobSkills   RAW COUNT — skills the job lists
 * @param matchedSkillCount RAW COUNT — job skills also found in the resume
 * @param missingSkillCount RAW COUNT — job skills absent from the resume, the skill gap
 * @param matchedSkills    the overlap
 * @param missingSkills    the gap: what this job asks for that the resume does not show
 * @param resumeOnlySkills skills in the resume this job does not ask for
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResumeMatchResponse(
        UUID resumeId,
        Long jobId,
        String jobTitle,
        String companyName,
        Double matchPercentage,
        String matchNote,
        int totalJobSkills,
        int totalResumeSkills,
        int matchedSkillCount,
        int missingSkillCount,
        List<SkillResponse> matchedSkills,
        List<SkillResponse> missingSkills,
        List<SkillResponse> resumeOnlySkills) {
}
