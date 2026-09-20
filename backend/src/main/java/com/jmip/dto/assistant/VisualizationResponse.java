package com.jmip.dto.assistant;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * How to draw the answer, when drawing it helps.
 *
 * <p>Metadata only: a type the frontend recognises, labels, and numbers that came from the
 * database. Never markup, never code, never a chart library call.
 *
 * @param type   which of the known shapes to use
 * @param title  heading for the chart
 * @param xAxis  what the labels are
 * @param yAxis  what the values measure
 * @param points the data, empty for {@link VisualizationType#NONE} and for TABLE, where
 *               the rows in {@code data} carry more columns than a point can hold
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VisualizationResponse(
        VisualizationType type,
        String title,
        String xAxis,
        String yAxis,
        List<ChartPoint> points) {

    /** No chart: the answer stands on its own. */
    public static VisualizationResponse none() {
        return new VisualizationResponse(VisualizationType.NONE, null, null, null, List.of());
    }

    public static VisualizationResponse bar(String title, String xAxis, String yAxis,
                                            List<ChartPoint> points) {
        return new VisualizationResponse(VisualizationType.BAR, title, xAxis, yAxis, points);
    }

    public static VisualizationResponse line(String title, String xAxis, String yAxis,
                                             List<ChartPoint> points) {
        return new VisualizationResponse(VisualizationType.LINE, title, xAxis, yAxis, points);
    }

    public static VisualizationResponse pie(String title, List<ChartPoint> points) {
        return new VisualizationResponse(VisualizationType.PIE, title, null, null, points);
    }

    /** A table of rows; the rows themselves travel in the response's {@code data}. */
    public static VisualizationResponse table(String title) {
        return new VisualizationResponse(VisualizationType.TABLE, title, null, null, List.of());
    }
}
