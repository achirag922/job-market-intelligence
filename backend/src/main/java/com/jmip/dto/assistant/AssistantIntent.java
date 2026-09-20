package com.jmip.dto.assistant;

import java.util.Optional;

/**
 * The questions this assistant knows how to answer.
 *
 * <p>A closed set on purpose. The model picks a name from this list and nothing else, and
 * each name maps to a service call that already existed before the assistant did. That is
 * what keeps a natural-language question from becoming an arbitrary query: there is no
 * path from a sentence to the database except through one of these.
 */
public enum AssistantIntent {

    /** Which skills are most asked for, optionally within a category or location. */
    SKILL_DEMAND,

    /** How demand for one skill has moved over time. */
    SKILL_TREND,

    /** How postings are spread across role categories. */
    JOB_CATEGORY_DEMAND,

    /** Which companies have the most postings. */
    COMPANY_DEMAND,

    /** Which places have the most postings. */
    LOCATION_DEMAND,

    /** Individual postings matching some filters. */
    JOB_SEARCH,

    /** What postings pay, reported per currency. */
    SALARY_ANALYSIS,

    /** Which skills a resume does not have for a category or a posting. */
    SKILL_GAP,

    /** How closely a resume matches one posting. */
    RESUME_MATCH,

    /** Two named skills, side by side. */
    SKILL_COMPARISON,

    /** Two named categories, side by side. */
    CATEGORY_COMPARISON,

    /** A summary of the dataset as a whole. */
    GENERAL_JOB_MARKET,

    /**
     * The question is not one of the above. Not a failure — a question can be perfectly
     * reasonable and still be outside what this dataset can answer, and saying so is a
     * better response than forcing it into the nearest intent.
     */
    UNSUPPORTED;

    /**
     * Parses a name supplied by the model.
     *
     * <p>Empty rather than throwing, and never defaulting to a real intent: an
     * unrecognised name means the model produced something outside the contract, and the
     * only safe reading of that is "no intent".
     */
    public static Optional<AssistantIntent> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException unknownName) {
            return Optional.empty();
        }
    }

    /** Whether answering needs a resume to have been selected. */
    public boolean requiresResume() {
        return this == SKILL_GAP || this == RESUME_MATCH;
    }
}
