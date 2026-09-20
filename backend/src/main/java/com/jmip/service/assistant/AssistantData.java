package com.jmip.service.assistant;

import com.jmip.dto.assistant.VisualizationResponse;

import java.util.List;

/**
 * What a question actually found.
 *
 * <p>The rows are the DTOs the ordinary endpoints already return — the assistant reuses
 * the same services, so it reports the same numbers as the rest of the application rather
 * than a second opinion computed a slightly different way.
 *
 * @param rows          retrieved records, empty when nothing matched
 * @param visualization how to draw them, decided here from the shape of the data
 * @param note          a caveat worth showing the user — a thin sample, a filter that
 *                      could not be applied — or null when there is none
 */
public record AssistantData(List<Object> rows, VisualizationResponse visualization, String note) {

    public static AssistantData of(List<?> rows, VisualizationResponse visualization) {
        return new AssistantData(List.copyOf(rows), visualization, null);
    }

    public static AssistantData of(List<?> rows, VisualizationResponse visualization, String note) {
        return new AssistantData(List.copyOf(rows), visualization, note);
    }

    /** Nothing matched, with a note explaining why when there is a reason worth giving. */
    public static AssistantData empty(String note) {
        return new AssistantData(List.of(), VisualizationResponse.none(), note);
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }
}
