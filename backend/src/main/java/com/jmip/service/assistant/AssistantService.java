package com.jmip.service.assistant;

import com.jmip.ai.AiClient;
import com.jmip.ai.AiFailureException;
import com.jmip.ai.AiUnavailableException;
import com.jmip.config.AssistantProperties;
import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.dto.assistant.AssistantIntent;
import com.jmip.dto.assistant.AssistantQueryRequest;
import com.jmip.dto.assistant.AssistantQueryResponse;
import com.jmip.dto.assistant.ConversationContext;
import com.jmip.dto.assistant.VisualizationResponse;
import com.jmip.service.resume.ResumeNotReadyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Answers one question, end to end.
 *
 * <p>The sequence is fixed and every step narrows what is possible:
 *
 * <ol>
 *   <li>the model proposes an intent,</li>
 *   <li>the validator checks it against the enum and the database,</li>
 *   <li>the router calls an existing analytics service,</li>
 *   <li>the database returns rows,</li>
 *   <li>the model describes those rows and nothing else.</li>
 * </ol>
 *
 * <p>The model appears twice and controls neither the query nor the figures. Between its
 * two turns sits validation it cannot influence and a fixed set of service calls it cannot
 * add to.
 *
 * <p>Nothing here throws for an unanswerable question. A question outside the dataset, a
 * skill that does not exist, a missing resume and an unreachable provider are all ordinary
 * outcomes with useful replies, and turning them into stack traces would make the assistant
 * fail where a sentence would do. Genuine faults still propagate to the global handler.
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    private static final String PROVIDER_UNAVAILABLE =
            "The assistant is not configured on this server, so I cannot answer questions "
                    + "right now. Everything else in the application works as usual.";

    private static final String PROVIDER_FAILED =
            "I could not process that question right now. Please try again.";

    private static final String NOT_UNDERSTOOD =
            "I could not work out what that question was asking. Could you rephrase it?";

    private static final String RESUME_REQUIRED =
            "Please upload or select a resume first, then ask again.";

    private final IntentExtractor intentExtractor;
    private final IntentValidator intentValidator;
    private final AnalyticsQueryRouter router;
    private final AnswerGenerator answerGenerator;
    private final AiClient aiClient;
    private final AssistantProperties properties;

    public AssistantService(IntentExtractor intentExtractor,
                            IntentValidator intentValidator,
                            AnalyticsQueryRouter router,
                            AnswerGenerator answerGenerator,
                            AiClient aiClient,
                            AssistantProperties properties) {
        this.intentExtractor = intentExtractor;
        this.intentValidator = intentValidator;
        this.router = router;
        this.answerGenerator = answerGenerator;
        this.aiClient = aiClient;
        this.properties = properties;
    }

    public AssistantQueryResponse answer(AssistantQueryRequest request) {
        long startedAt = System.nanoTime();
        String question = trimQuestion(request.question());

        // The question itself is never logged: it is user input and can carry anything.
        log.info("Assistant question received ({} chars, resume={}, provider={})",
                question.length(), request.resumeId() != null, aiClient.providerName());

        AssistantQueryResponse response = process(request, question);

        log.info("Assistant answered intent={} grounded={} rows={} in {}ms",
                response.intent(), response.grounded(),
                response.data() == null ? 0 : response.data().size(),
                (System.nanoTime() - startedAt) / 1_000_000);
        return response;
    }

    private AssistantQueryResponse process(AssistantQueryRequest request, String question) {
        if (!aiClient.isAvailable()) {
            return ungrounded(question, PROVIDER_UNAVAILABLE, AssistantIntent.UNSUPPORTED,
                    request.contextOrEmpty(), null);
        }

        ConversationContext context = request.contextOrEmpty();

        Optional<IntentExtraction> extraction;
        try {
            extraction = intentExtractor.extract(question, context);
        } catch (AiUnavailableException unavailable) {
            return ungrounded(question, PROVIDER_UNAVAILABLE, AssistantIntent.UNSUPPORTED, context, null);
        } catch (AiFailureException failure) {
            // Timeout, rate limit, transport, provider error. Already logged with its type
            // by the client; the user gets one sentence and no internal detail.
            return ungrounded(question, PROVIDER_FAILED, AssistantIntent.UNSUPPORTED, context, null);
        }

        if (extraction.isEmpty()) {
            // The model replied with something that was not usable JSON. Nothing can be
            // validated, so nothing is run.
            return ungrounded(question, NOT_UNDERSTOOD, AssistantIntent.UNSUPPORTED, context, null);
        }

        IntentValidation validation = intentValidator.validate(extraction.get(), context);
        if (!validation.isAccepted()) {
            return ungrounded(question, validation.rejection(), AssistantIntent.UNSUPPORTED, context, null);
        }

        ResolvedIntent intent = validation.intent();

        UUID resumeId = request.resumeId();
        if (intent.intent().requiresResume() && resumeId == null) {
            // V7.6: no resume selected means the signed-in user's own default, never anyone else's.
            resumeId = router.defaultResumeId().orElse(null);
        }
        if (intent.intent().requiresResume() && resumeId == null) {
            return ungrounded(question, RESUME_REQUIRED, intent.intent(),
                    contextOf(question, intent), null);
        }

        AssistantData data;
        try {
            data = router.route(intent, resumeId, request.jobId());
        } catch (ResourceNotFoundException notFound) {
            // A resume or posting id the caller supplied does not exist. Their input, not
            // a fault: the message names what was missing and no query ran.
            return ungrounded(question, notFound.getMessage(), intent.intent(),
                    contextOf(question, intent), null);
        } catch (ResumeNotReadyException notReady) {
            return ungrounded(question, notReady.getMessage(), intent.intent(),
                    contextOf(question, intent), null);
        }

        return describe(question, intent, data);
    }

    /**
     * Adds the prose to rows that have already been retrieved.
     *
     * <p>A provider failure at this point loses the sentence, not the answer. The rows are
     * real and already in hand, so they are returned with a plain description rather than
     * thrown away — the user still gets their numbers and their chart.
     */
    private AssistantQueryResponse describe(String question, ResolvedIntent intent,
                                            AssistantData data) {
        String answer;
        try {
            answer = answerGenerator.describe(question, data);
        } catch (AiUnavailableException | AiFailureException failure) {
            answer = data.isEmpty()
                    ? "No matching data was found in this dataset."
                    : "Here is what the dataset holds for that question.";
            log.warn("Answer generation failed; returning retrieved rows without prose");
        }

        return new AssistantQueryResponse(
                question,
                answer,
                intent.intent(),
                true,
                data.rows(),
                data.visualization(),
                contextOf(question, intent),
                data.note());
    }

    /**
     * A reply with no data behind it.
     *
     * <p>{@code grounded} is false and {@code data} is empty, so a caller can tell at a
     * glance that this sentence is the assistant talking about itself rather than about the
     * dataset. Nothing here is ever phrased as a fact about the job market.
     */
    private static AssistantQueryResponse ungrounded(String question, String answer,
                                                     AssistantIntent intent,
                                                     ConversationContext context, String note) {
        return new AssistantQueryResponse(
                question, answer, intent, false, List.of(),
                VisualizationResponse.none(), context, note);
    }

    /** What this turn was about, for the next question to refer back to. */
    private static ConversationContext contextOf(String question, ResolvedIntent intent) {
        return new ConversationContext(
                question, intent.intent(), intent.entities().toContextEntities());
    }

    /**
     * Bounds the question before it reaches a prompt.
     *
     * <p>The DTO already caps it; this is the configurable operational limit, and it
     * truncates rather than rejecting because a long question is usually a paste with the
     * real question at the front.
     */
    private String trimQuestion(String question) {
        String trimmed = question.trim();
        return trimmed.length() > properties.maxQuestionLength()
                ? trimmed.substring(0, properties.maxQuestionLength())
                : trimmed;
    }
}
