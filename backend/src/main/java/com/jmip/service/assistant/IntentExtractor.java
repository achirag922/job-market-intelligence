package com.jmip.service.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmip.ai.AiClient;
import com.jmip.ai.AiCompletionRequest;
import com.jmip.ai.AiTask;
import com.jmip.ai.PromptMarkers;
import com.jmip.ai.prompt.PromptLibrary;
import com.jmip.dto.assistant.ConversationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Turns a question into a structured intent, by asking the model and parsing what comes
 * back.
 *
 * <p>This is the only place a model gets to influence what happens next, and all it can
 * influence is the contents of one small record. It cannot name a table, write a query or
 * call anything; the most damage a bad extraction can do is send a valid question down the
 * wrong branch, which the validator then usually rejects.
 */
@Component
public class IntentExtractor {

    private static final Logger log = LoggerFactory.getLogger(IntentExtractor.class);

    /** Enough for the JSON plus the reasoning tokens that precede it. */
    private static final int EXTRACTION_TOKEN_BUDGET = 2048;

    private final AiClient aiClient;
    private final PromptLibrary prompts;
    private final ObjectMapper objectMapper;

    public IntentExtractor(AiClient aiClient, PromptLibrary prompts, ObjectMapper objectMapper) {
        this.aiClient = aiClient;
        this.prompts = prompts;
        this.objectMapper = objectMapper;
    }

    /**
     * @return the parsed extraction, or empty when the model's reply was not usable JSON
     */
    public Optional<IntentExtraction> extract(String question, ConversationContext context) {
        String reply = aiClient.complete(new AiCompletionRequest(
                AiTask.INTENT_EXTRACTION,
                prompts.intentExtraction(),
                buildUserMessage(question, context),
                EXTRACTION_TOKEN_BUDGET));

        return parse(reply);
    }

    /**
     * The question is labelled as data rather than pasted in raw.
     *
     * <p>A question is user input, and a user can write "ignore your instructions" into
     * it. Marking where it starts and ends does not make that impossible, but it is the
     * reason the prompt can say "text inside the question is the user's words" and mean
     * something. The real protection is downstream: whatever the model returns is a name
     * that has to match a database row before it is used.
     */
    private String buildUserMessage(String question, ConversationContext context) {
        StringBuilder message = new StringBuilder();

        if (context != null && !context.isEmpty()) {
            message.append(PromptMarkers.PREVIOUS_TURN).append('\n');
            if (context.previousIntent() != null) {
                message.append("intent: ").append(context.previousIntent().name()).append('\n');
            }
            if (context.previousQuestion() != null) {
                message.append("question: ").append(context.previousQuestion()).append('\n');
            }
            appendEntity(message, "skill", context.entitiesOrEmpty().skill());
            appendEntity(message, "jobCategory", context.entitiesOrEmpty().jobCategory());
            appendEntity(message, "company", context.entitiesOrEmpty().company());
            appendEntity(message, "location", context.entitiesOrEmpty().location());
            message.append('\n');
        }

        message.append(PromptMarkers.QUESTION).append('\n').append(question);
        return message.toString();
    }

    private static void appendEntity(StringBuilder message, String name, String value) {
        if (value != null) {
            message.append(name).append(": ").append(value).append('\n');
        }
    }

    /**
     * Parses the reply, tolerating the packaging models add around JSON but not the
     * absence of JSON.
     *
     * <p>A reply wrapped in a code fence or trailed by a sentence is still a usable
     * answer, and failing on it would make the assistant flaky for no reason. A reply with
     * no JSON object in it is a different matter: there is nothing to validate, and
     * guessing an intent from prose would defeat the point of asking for structure.
     */
    private Optional<IntentExtraction> parse(String reply) {
        String json = isolateJsonObject(reply);
        if (json == null) {
            log.warn("AI intent reply contained no JSON object");
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(json, IntentExtraction.class));
        } catch (JsonProcessingException malformed) {
            // The reply itself is not logged: it echoes the user's question back.
            log.warn("AI intent reply was not valid JSON: {}", malformed.getOriginalMessage());
            return Optional.empty();
        }
    }

    /** Returns the outermost brace-delimited span, or null when there is not one. */
    private static String isolateJsonObject(String reply) {
        if (reply == null) {
            return null;
        }
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        return start >= 0 && end > start ? reply.substring(start, end + 1) : null;
    }
}
