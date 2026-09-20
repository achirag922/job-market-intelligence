package com.jmip.etl.model;

import com.jmip.etl.config.ClassificationProperties;

import java.util.List;

/**
 * What kind of role a posting is, and the evidence behind that call.
 *
 * @param category   the winning category, or {@code Other} when nothing matched
 * @param confidence 0 to 100: how much evidence there was and how clearly it beat the
 *                   alternatives. Not a probability that the category is correct
 * @param signals    exactly what matched, so the classification can be explained
 */
public record JobClassification(String category, double confidence, List<Signal> signals) {

    public JobClassification {
        signals = signals == null ? List.of() : List.copyOf(signals);
    }

    /** Nothing matched: recorded as its own outcome rather than a forced guess. */
    public static JobClassification unclassified() {
        return new JobClassification(ClassificationProperties.UNCLASSIFIED_CATEGORY, 0.0, List.of());
    }

    public enum SignalType {
        TITLE,
        DESCRIPTION,
        SKILL
    }

    /**
     * @param value  the keyword or skill that matched, as written in the rules
     * @param weight what it contributed to the score
     */
    public record Signal(SignalType type, String value, double weight) {
    }
}
