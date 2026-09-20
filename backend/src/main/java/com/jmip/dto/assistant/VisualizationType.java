package com.jmip.dto.assistant;

/**
 * The chart shapes the frontend knows how to draw.
 *
 * <p>A closed set, chosen by the backend rather than by the model. The frontend maps each
 * name to a component it already has; there is no path by which a model could describe a
 * chart the frontend would build from arbitrary instructions.
 */
public enum VisualizationType {

    /** Ranked categories — skills, companies, locations. */
    BAR,

    /** A value over time. */
    LINE,

    /** Parts of a whole, used only where the parts genuinely sum to one. */
    PIE,

    /** Rows with several columns worth seeing, such as job postings. */
    TABLE,

    /** The answer is a sentence and a chart would add nothing. */
    NONE
}
