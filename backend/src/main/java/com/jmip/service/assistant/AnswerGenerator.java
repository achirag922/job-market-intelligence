package com.jmip.service.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmip.ai.AiClient;
import com.jmip.ai.AiCompletionRequest;
import com.jmip.ai.AiTask;
import com.jmip.ai.prompt.PromptLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Turns retrieved rows into a sentence.
 *
 * <p>The model sees the question and the rows, and nothing else — no database access, no
 * tools, no earlier turns. Everything it could truthfully say is in front of it, which is
 * the only reliable way to keep the prose and the figures in agreement.
 *
 * <p>Grounding is a property of the architecture rather than of the prompt. The numbers the
 * caller displays are the rows themselves; this text is a description sitting beside them.
 * If the model were to invent a figure anyway, it would contradict the table rendered
 * directly underneath it rather than pass as fact.
 */
@Component
public class AnswerGenerator {

    private static final Logger log = LoggerFactory.getLogger(AnswerGenerator.class);

    /** Two or three sentences, plus the reasoning tokens that precede them. */
    private static final int ANSWER_TOKEN_BUDGET = 1024;

    /**
     * Rows sent to the model. The caller may be showing more than this; the extra rows add
     * prompt cost without changing a two-sentence summary, and the tail of a ranked list is
     * the least interesting part of it.
     */
    private static final int MAX_ROWS_IN_PROMPT = 15;

    private final AiClient aiClient;
    private final PromptLibrary prompts;
    private final ObjectMapper objectMapper;

    public AnswerGenerator(AiClient aiClient, PromptLibrary prompts, ObjectMapper objectMapper) {
        this.aiClient = aiClient;
        this.prompts = prompts;
        this.objectMapper = objectMapper;
    }

    /**
     * @param question the user's original words, so the answer addresses what was asked
     * @param data     what the database returned
     * @return prose describing {@code data}
     */
    public String describe(String question, AssistantData data) {
        String payload = serialise(question, data);
        String answer = aiClient.complete(new AiCompletionRequest(
                AiTask.ANSWER_GENERATION, prompts.answerGeneration(), payload, ANSWER_TOKEN_BUDGET));

        log.debug("Generated a {}-character answer over {} rows", answer.length(), data.rows().size());
        return answer;
    }

    /**
     * Builds the payload.
     *
     * <p>Serialised through the same {@link ObjectMapper} the API uses, so the model reads
     * the rows with the same field names and the same rounding the user sees. A separate
     * formatting path here would be a second place for the numbers to drift.
     */
    private String serialise(String question, AssistantData data) {
        List<Object> rows = data.rows().size() > MAX_ROWS_IN_PROMPT
                ? data.rows().subList(0, MAX_ROWS_IN_PROMPT)
                : data.rows();

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("question", question);
        payload.put("rowCount", data.rows().size());
        payload.put("rows", rows);
        if (data.note() != null) {
            payload.put("note", data.note());
        }

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            // Falling back to an empty payload would invite the model to answer from
            // general knowledge, which is the one thing it must never do here.
            throw new IllegalStateException("Could not serialise assistant data", exception);
        }
    }
}
