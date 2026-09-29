package com.jmip.dto.resume;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * V8.3: how a resume and the user's preferences compare with one job, dimension by
 * dimension. Deterministic and explainable: every score comes with the rule that produced
 * it, and a dimension without data is {@code UNAVAILABLE}, never guessed. It is a
 * compatibility measure, not a prediction of interviews or hiring.
 *
 * @param overallPercentage the weighted average of the available dimensions' scores;
 *                          null when the posting lists no skills
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MatchBreakdown(
        Double overallPercentage,
        Dimension skills,
        Dimension experience,
        Dimension location,
        Dimension workMode,
        Dimension salary,
        // V9.3: career-goal alignment and the V9.2 role and skill preferences.
        Dimension careerGoal,
        Dimension role,
        Dimension preferredSkills,
        /** Required skills the resume does not show, by name. */
        java.util.List<String> missingRequiredSkills,
        /** Skills the posting only lists as nice to have; they never lower the skill score. */
        java.util.List<String> optionalSkills,
        /** Each available dimension's contribution, in words. */
        java.util.List<String> reasons) {

    /** The V8.3 shape. */
    public MatchBreakdown(Double overallPercentage, Dimension skills, Dimension experience, Dimension location,
                          Dimension workMode, Dimension salary) {
        this(overallPercentage, skills, experience, location, workMode, salary, null, null, null, null, null, null);
    }

    public enum Status { MATCH, PARTIAL, NO_MATCH, UNAVAILABLE }

    /**
     * @param score  0 to 100; null when unavailable
     * @param weight this dimension's share of the overall score when it is available
     * @param detail why, in words
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Dimension(Status status, Double score, int weight, String detail) {

        public static Dimension unavailable(int weight, String detail) {
            return new Dimension(Status.UNAVAILABLE, null, weight, detail);
        }

        public boolean available() {
            return status != Status.UNAVAILABLE;
        }
    }
}
