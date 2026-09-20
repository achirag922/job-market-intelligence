package com.jmip.service.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmip.ai.AiFailureException;
import com.jmip.ai.AiTask;
import com.jmip.ai.prompt.PromptLibrary;
import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.config.AssistantProperties;
import com.jmip.dto.analytics.SkillDemandResponse;
import com.jmip.dto.assistant.AssistantIntent;
import com.jmip.dto.assistant.AssistantQueryRequest;
import com.jmip.dto.assistant.AssistantQueryResponse;
import com.jmip.dto.assistant.VisualizationResponse;
import com.jmip.dto.assistant.VisualizationType;
import com.jmip.service.resume.ResumeNotReadyException;
import com.jmip.testsupport.ScriptedAiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The assistant end to end, with a scripted provider.
 *
 * <p>Most of these are failure cases, because that is where an assistant is judged. Nothing
 * a provider can do — time out, rate-limit, return prose, return an intent that does not
 * exist, disappear entirely — may produce a stack trace, leak an internal detail, or make
 * the application state a fact it has not retrieved.
 */
class AssistantServiceTest {

    private final PromptLibrary prompts = new PromptLibrary();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AssistantProperties properties = new AssistantProperties(10, 25, 20, 5, 500);

    private final IntentValidator validator = mock(IntentValidator.class);
    private final AnalyticsQueryRouter router = mock(AnalyticsQueryRouter.class);

    private AssistantService serviceFor(ScriptedAiClient client) {
        return new AssistantService(
                new IntentExtractor(client, prompts, objectMapper),
                validator,
                router,
                new AnswerGenerator(client, prompts, objectMapper),
                client,
                properties);
    }

    private static AssistantQueryRequest ask(String question) {
        return new AssistantQueryRequest(question, null, null, null);
    }

    // ------------------------------------------------------------- happy path

    @Test
    @DisplayName("a understood question returns rows, a chart and a grounded flag")
    void answersGroundedQuestion() {
        acceptIntent(AssistantIntent.SKILL_DEMAND);
        when(router.route(any(), any(), any())).thenReturn(AssistantData.of(
                List.of(new SkillDemandResponse(1L, "Java", "LANGUAGE", 34, 24.6, 1)),
                VisualizationResponse.bar("Top skills", "Skill", "Job count", List.of())));

        AssistantQueryResponse response = serviceFor(new ScriptedAiClient()
                .respondingWithAnswer("Java appears most often.")).answer(ask("top skills"));

        assertThat(response.grounded()).isTrue();
        assertThat(response.intent()).isEqualTo(AssistantIntent.SKILL_DEMAND);
        assertThat(response.data()).hasSize(1);
        assertThat(response.answer()).isEqualTo("Java appears most often.");
        assertThat(response.visualization().type()).isEqualTo(VisualizationType.BAR);
    }

    @Test
    @DisplayName("the answer call is given the retrieved rows, and only those")
    void groundsTheAnswerInRetrievedRows() {
        // The model writing the prose must have the figures in front of it and no way to
        // reach any others. This asserts what it was actually sent.
        acceptIntent(AssistantIntent.SKILL_DEMAND);
        when(router.route(any(), any(), any())).thenReturn(AssistantData.of(
                List.of(new SkillDemandResponse(1L, "Java", "LANGUAGE", 34, 24.6, 1)),
                VisualizationResponse.none()));

        ScriptedAiClient client = new ScriptedAiClient();
        serviceFor(client).answer(ask("top skills"));

        var answerCall = client.requests().stream()
                .filter(request -> request.task() == AiTask.ANSWER_GENERATION)
                .findFirst()
                .orElseThrow();

        assertThat(answerCall.user()).contains("\"skill\":\"Java\"").contains("34");
        assertThat(answerCall.system()).contains("ONLY numbers, names and rankings");
    }

    @Test
    @DisplayName("this turn is returned as context for the next question")
    void returnsConversationContext() {
        acceptIntent(AssistantIntent.SKILL_DEMAND);
        when(router.route(any(), any(), any()))
                .thenReturn(AssistantData.of(List.of(), VisualizationResponse.none()));

        AssistantQueryResponse response = serviceFor(new ScriptedAiClient()).answer(ask("top skills"));

        assertThat(response.context().previousQuestion()).isEqualTo("top skills");
        assertThat(response.context().previousIntent()).isEqualTo(AssistantIntent.SKILL_DEMAND);
    }

    // -------------------------------------------------------- empty results

    @Test
    @DisplayName("an empty result is still grounded, and says nothing was found")
    void emptyResultIsGroundedAndHonest() {
        // The query ran and returned nothing. That is a fact about the dataset, and quite
        // different from not having looked.
        acceptIntent(AssistantIntent.SKILL_DEMAND);
        when(router.route(any(), any(), any()))
                .thenReturn(AssistantData.empty("Nothing matched those filters."));

        AssistantQueryResponse response = serviceFor(new ScriptedAiClient()
                .respondingWithAnswer("No matching data was found."))
                .answer(ask("top skills for nothing"));

        assertThat(response.grounded()).isTrue();
        assertThat(response.data()).isEmpty();
        assertThat(response.note()).isEqualTo("Nothing matched those filters.");
    }

    // ------------------------------------------------------- provider failures

    @Test
    @DisplayName("no configured provider is reported plainly, and nothing is queried")
    void handlesUnavailableProvider() {
        AssistantQueryResponse response =
                serviceFor(new ScriptedAiClient().unavailable()).answer(ask("top skills"));

        assertThat(response.grounded()).isFalse();
        assertThat(response.answer()).contains("not configured");
        verify(router, never()).route(any(), any(), any());
    }

    @Test
    @DisplayName("a provider timeout becomes one sentence, with no internal detail")
    void handlesTimeout() {
        AssistantQueryResponse response = serviceFor(new ScriptedAiClient()
                .failingIntentWith(ScriptedAiClient.timeout())).answer(ask("top skills"));

        assertThat(response.grounded()).isFalse();
        assertThat(response.answer()).isEqualTo("I could not process that question right now. Please try again.");
        // No exception class, no stack frame, no provider wording.
        assertThat(response.answer()).doesNotContain("SocketTimeout").doesNotContain("Exception");
    }

    @Test
    @DisplayName("a rate-limit rejection is handled like any other provider failure")
    void handlesRateLimit() {
        AssistantQueryResponse response = serviceFor(new ScriptedAiClient()
                .failingIntentWith(ScriptedAiClient.rateLimited())).answer(ask("top skills"));

        assertThat(response.grounded()).isFalse();
        assertThat(response.answer()).contains("Please try again");
        assertThat(response.answer()).doesNotContain("429").doesNotContain("rate_limit");
    }

    @Test
    @DisplayName("a reply that is not JSON is reported as not understood")
    void handlesMalformedProviderReply() {
        AssistantQueryResponse response = serviceFor(new ScriptedAiClient()
                .respondingWithIntent("I cannot help with that.")).answer(ask("top skills"));

        assertThat(response.grounded()).isFalse();
        assertThat(response.answer()).contains("rephrase");
        verify(router, never()).route(any(), any(), any());
    }

    @Test
    @DisplayName("losing the provider after retrieval keeps the rows and drops only the prose")
    void keepsRowsWhenAnswerGenerationFails() {
        // The numbers are already in hand and they are the valuable part. Throwing them
        // away because a sentence could not be written would be the wrong trade.
        acceptIntent(AssistantIntent.SKILL_DEMAND);
        when(router.route(any(), any(), any())).thenReturn(AssistantData.of(
                List.of(new SkillDemandResponse(1L, "Java", "LANGUAGE", 34, 24.6, 1)),
                VisualizationResponse.bar("Top skills", "Skill", "Job count", List.of())));

        AssistantQueryResponse response = serviceFor(new ScriptedAiClient()
                .failingAnswerWith(new AiFailureException("provider down")))
                .answer(ask("top skills"));

        assertThat(response.grounded()).isTrue();
        assertThat(response.data()).hasSize(1);
        assertThat(response.answer()).isNotBlank();
        assertThat(response.visualization().type()).isEqualTo(VisualizationType.BAR);
    }

    // ----------------------------------------------------------- rejections

    @Test
    @DisplayName("a rejected intent returns the validator's message and queries nothing")
    void returnsValidatorRejection() {
        when(validator.validate(any(), any()))
                .thenReturn(IntentValidation.rejected("I could not find the skill \"Cobol\"."));

        AssistantQueryResponse response = serviceFor(new ScriptedAiClient()).answer(ask("cobol trend"));

        assertThat(response.grounded()).isFalse();
        assertThat(response.answer()).contains("Cobol");
        verify(router, never()).route(any(), any(), any());
    }

    @Test
    @DisplayName("a resume question with no resume asks for one instead of answering")
    void requiresResumeForResumeIntents() {
        acceptIntent(AssistantIntent.SKILL_GAP);

        AssistantQueryResponse response =
                serviceFor(new ScriptedAiClient()).answer(ask("what skills am I missing"));

        assertThat(response.grounded()).isFalse();
        assertThat(response.answer()).isEqualTo("Please upload or select a resume first, then ask again.");
        verify(router, never()).route(any(), any(), any());
    }

    @Test
    @DisplayName("a resume question with a resume is routed")
    void routesResumeIntentWithResume() {
        acceptIntent(AssistantIntent.SKILL_GAP);
        when(router.route(any(), any(), any()))
                .thenReturn(AssistantData.of(List.of(), VisualizationResponse.none()));

        AssistantQueryResponse response = serviceFor(new ScriptedAiClient()).answer(
                new AssistantQueryRequest("what am I missing", UUID.randomUUID(), null, null));

        assertThat(response.grounded()).isTrue();
    }

    @Test
    @DisplayName("an id that does not exist is an explanation, not a stack trace")
    void handlesMissingResource() {
        acceptIntent(AssistantIntent.RESUME_MATCH);
        when(router.route(any(), any(), any()))
                .thenThrow(ResourceNotFoundException.of("Job", 999L));

        AssistantQueryResponse response = serviceFor(new ScriptedAiClient()).answer(
                new AssistantQueryRequest("match me", UUID.randomUUID(), 999L, null));

        assertThat(response.grounded()).isFalse();
        assertThat(response.answer()).contains("Job");
    }

    @Test
    @DisplayName("a resume that is still processing is explained rather than thrown")
    void handlesResumeNotReady() {
        acceptIntent(AssistantIntent.RESUME_MATCH);
        when(router.route(any(), any(), any()))
                .thenThrow(new ResumeNotReadyException("That resume is still being processed."));

        AssistantQueryResponse response = serviceFor(new ScriptedAiClient()).answer(
                new AssistantQueryRequest("match me", UUID.randomUUID(), 1L, null));

        assertThat(response.grounded()).isFalse();
        assertThat(response.answer()).contains("still being processed");
    }

    // ------------------------------------------------------------- boundaries

    @Test
    @DisplayName("an over-long question is truncated before it reaches a prompt")
    void truncatesLongQuestions() {
        acceptIntent(AssistantIntent.SKILL_DEMAND);
        when(router.route(any(), any(), any()))
                .thenReturn(AssistantData.of(List.of(), VisualizationResponse.none()));

        ScriptedAiClient client = new ScriptedAiClient();
        serviceFor(client).answer(ask("x".repeat(5000)));

        assertThat(client.requests().get(0).user()).hasSizeLessThan(5000);
    }

    private void acceptIntent(AssistantIntent intent) {
        when(validator.validate(any(), any())).thenReturn(IntentValidation.accepted(
                new ResolvedIntent(intent, ResolvedEntities.empty(), null, 10)));
    }
}
