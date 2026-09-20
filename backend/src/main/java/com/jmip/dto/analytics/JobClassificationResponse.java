package com.jmip.dto.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Why a posting was put in its category.
 *
 * <p>Returned with the job so the classification can be shown rather than asserted. The
 * signals are exactly what matched, with what each contributed.
 *
 * @param category   the assigned category
 * @param confidence DERIVED — 0 to 100, how strong and how clear-cut the evidence was.
 *                   Not a probability that the category is correct
 * @param signals    the matched evidence, strongest first
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobClassificationResponse(String category, Double confidence, List<Signal> signals) {

    /**
     * @param type  TITLE, DESCRIPTION or SKILL — where the match came from
     * @param value the keyword or skill that matched
     * @param weight what it contributed to the score
     */
    public record Signal(String type, String value, double weight) {
    }
}
