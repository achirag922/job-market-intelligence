package com.jmip.service.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmip.ai.StubAiClient;
import com.jmip.ai.prompt.PromptLibrary;
import com.jmip.dto.assistant.AssistantIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** V7.6: the copilot questions reach their intents through the offline (stub) provider and the v2 prompt. */
class CopilotIntentRoutingTest {

    private final PromptLibrary prompts = new PromptLibrary();
    private final IntentExtractor extractor = new IntentExtractor(new StubAiClient(), prompts, new ObjectMapper());

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "What skills am I missing for my target role?|MY_SKILL_GAP",
            "Which jobs match my resume?|MY_JOB_MATCHES",
            "Why is my resume match score low?|RESUME_IMPROVEMENT",
            "What skills should I focus on next?|NEXT_SKILLS",
            "How is demand for my target role changing?|TARGET_ROLE_DEMAND",
            "Which skills are currently most requested for my target role?|TARGET_ROLE_SKILLS",
            "Show me my application progress.|APPLICATION_PROGRESS",
            "Which saved jobs should I prioritize?|SAVED_JOB_PRIORITY",
            "How does my resume compare with this job?|RESUME_MATCH",
            "What should I improve in my resume?|RESUME_IMPROVEMENT",
            // The V5 questions keep their intents.
            "what skills am I missing for data engineer jobs|SKILL_GAP",
            "how well does my resume match|RESUME_MATCH",
            "which companies are hiring the most|COMPANY_DEMAND"})
    @DisplayName("each career question is routed to its intent")
    void routing(String question, String expected) {
        assertThat(extractor.extract(question, null)).get()
                .extracting(IntentExtraction::intent)
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("the v2 prompt offers every intent, and forbids naming another user")
    void promptListsTheIntents() {
        Arrays.stream(AssistantIntent.values())
                .forEach(intent -> assertThat(prompts.intentExtraction()).contains(intent.name()));
        assertThat(prompts.intentExtraction()).contains("Never output a user, account");
        assertThat(prompts.answerGeneration()).contains("Never predict whether the user will be invited to interview");
        assertThat(prompts.versions()).contains("intent-extraction-v2", "answer-generation-v2");
    }

    @Test
    @DisplayName("resume-dependent copilot intents are marked as needing a resume")
    void resumeRequirement() {
        assertThat(AssistantIntent.MY_JOB_MATCHES.requiresResume()).isTrue();
        assertThat(AssistantIntent.RESUME_IMPROVEMENT.requiresResume()).isTrue();
        assertThat(AssistantIntent.APPLICATION_PROGRESS.requiresResume()).isFalse();
        assertThat(AssistantIntent.MY_SKILL_GAP.requiresResume()).isFalse();
    }
}
