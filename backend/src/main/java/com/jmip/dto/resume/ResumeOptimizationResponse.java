package com.jmip.dto.resume;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jmip.dto.ExperienceResponse;
import com.jmip.dto.SkillResponse;

import java.util.List;
import java.util.UUID;

/**
 * V8.6: one of the user's resumes against one job, with what to improve. Everything here comes
 * from the resume's own text and skills and the posting's own data; nothing is written for the
 * user, and no skill or experience is suggested that the resume does not already evidence
 * unless the user actually has it.
 *
 * @param breakdown       the V8.3 match, dimension by dimension
 * @param presentKeywords posting terms the resume already uses
 * @param missingKeywords posting terms the resume does not use
 * @param overusedKeywords terms the resume repeats heavily (a stuffing warning, not a target)
 * @param sectionsFound   the resume's recognised section headings
 * @param keywordNote     why keyword analysis is unavailable, when it is
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResumeOptimizationResponse(
        UUID resumeId,
        String resumeTitle,
        String resumeVersionLabel,
        Long jobId,
        String jobTitle,
        String companyName,
        Double overallMatchPercentage,
        Double skillMatchPercentage,
        MatchBreakdown breakdown,
        List<SkillResponse> matchedSkills,
        List<SkillResponse> missingSkills,
        List<SkillResponse> otherResumeSkills,
        ExperienceResponse requiredExperience,
        String experienceGap,
        List<Keyword> presentKeywords,
        List<Keyword> missingKeywords,
        List<Keyword> overusedKeywords,
        String keywordNote,
        List<String> sectionsFound,
        List<String> sectionsMissing,
        List<Suggestion> suggestions,
        String disclaimer) {

    public record Keyword(String term, int jobMentions, int resumeMentions) {
    }

    /** One suggestion, grouped by what it is about: SKILLS, KEYWORDS, SECTIONS, EXPERIENCE or ALIGNMENT. */
    public record Suggestion(String area, String text) {
    }
}
