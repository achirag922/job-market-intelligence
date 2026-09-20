package com.jmip.dto.assistant;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The assistant's reply: what it understood, what it found, and how to show it.
 *
 * <p>{@code answer} is prose written by a model. {@code data} is rows read from the
 * database. They are separate fields because they have different standing — the rows are
 * the evidence, the prose only describes them — and a caller that does not trust the prose
 * can render the rows and ignore it entirely.
 *
 * @param question      the question as asked, echoed so a transcript needs no other state
 * @param answer        a short natural-language description of {@code data}
 * @param intent        what the question was understood to be
 * @param grounded      true when {@code data} came from a database query. False for
 *                      answers that could not be grounded — an unsupported question, a
 *                      missing resume, an unavailable provider — so a caller never has to
 *                      infer whether the prose is backed by anything
 * @param data          the retrieved rows, in the same DTOs the ordinary endpoints return
 * @param visualization how to chart it, or {@link VisualizationType#NONE}
 * @param context       this turn, to send back with the next question
 * @param note          why a question could not be answered fully, when that happened
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssistantQueryResponse(
        String question,
        String answer,
        AssistantIntent intent,
        boolean grounded,
        List<Object> data,
        VisualizationResponse visualization,
        ConversationContext context,
        String note) {
}
