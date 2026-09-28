package com.jmip.dto.resume;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * V8.6: two of the user's resume versions against one job: the V7.3 skill comparison, each
 * version's V8.3 match, and which of the posting's terms the second version gained or lost.
 *
 * @param overallChange second minus first, in percentage points; absent when either has no score
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResumeJobComparisonResponse(
        ResumeComparisonResponse versions,
        Long jobId,
        String jobTitle,
        Score first,
        Score second,
        Double overallChange,
        Double skillChange,
        List<String> keywordsGained,
        List<String> keywordsLost) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Score(Double overallMatchPercentage, Double skillMatchPercentage, int matchedSkillCount,
                        int missingSkillCount) {
    }
}
