package com.jmip.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jmip.dto.resume.MatchBreakdown;

import java.util.List;

/**
 * V9.2: the signed-in user's job feed, most relevant first. Each job carries its V8.3 match and the
 * reasons it is ranked where it is; the priority is a ranking aid, not a hiring or interview chance.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PersonalizedFeedResponse(List<Item> jobs, Context context, String note) {

    /**
     * @param matchPercentage the V8.3 overall match with the current resume and preferences; absent without a resume
     * @param priority        the match plus the personal signals listed in {@code reasons}; what the feed is sorted by
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(JobSummaryResponse job, Double matchPercentage, double priority, boolean saved,
                       List<Reason> reasons, MatchBreakdown breakdown) {
    }

    /**
     * @param kind   POSITIVE, NEGATIVE or INFO
     * @param points what it added to the priority, when it added any
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Reason(String text, String kind, Integer points) {
    }

    /** What the feed was personalised with. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Context(boolean resume, String careerGoal, List<String> preferredRoles, List<String> preferredSkills,
                          int candidates, int excludedApplied, int excludedByPreference) {
    }
}
