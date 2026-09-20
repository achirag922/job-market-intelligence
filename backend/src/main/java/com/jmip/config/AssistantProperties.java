package com.jmip.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits on what one assistant question may retrieve.
 *
 * <p>These exist because the model chooses the numbers. A question can ask for "all the
 * jobs", and the extracted intent will faithfully carry a huge limit; the backend clamps
 * it rather than trusting it.
 *
 * @param defaultLimit     rows returned when the question implies no size
 * @param maxLimit         ceiling for analytics rows, whatever the model asked for
 * @param maxJobResults    ceiling for job search results specifically, which are far
 *                         heavier rows than an analytics count
 * @param minSalarySample  fewest postings in one currency before a salary range is
 *                         reported at all. Below this the honest answer is that the
 *                         dataset does not have enough salary data
 * @param maxQuestionLength longest question accepted, so a pasted document cannot become
 *                         a prompt
 */
@ConfigurationProperties(prefix = "jmip.assistant")
public record AssistantProperties(
        @DefaultValue("10") int defaultLimit,
        @DefaultValue("25") int maxLimit,
        @DefaultValue("20") int maxJobResults,
        @DefaultValue("5") int minSalarySample,
        @DefaultValue("500") int maxQuestionLength) {

    /** Clamps a model-supplied limit into the configured range. */
    public int clampLimit(Integer requested) {
        if (requested == null || requested < 1) {
            return defaultLimit;
        }
        return Math.min(requested, maxLimit);
    }

    /** Job rows are heavier than analytics rows, so they have their own, lower ceiling. */
    public int clampJobLimit(Integer requested) {
        if (requested == null || requested < 1) {
            return Math.min(defaultLimit, maxJobResults);
        }
        return Math.min(requested, maxJobResults);
    }
}
