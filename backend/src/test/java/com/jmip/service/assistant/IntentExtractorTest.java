package com.jmip.service.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmip.ai.AiTask;
import com.jmip.ai.prompt.PromptLibrary;
import com.jmip.dto.assistant.AssistantIntent;
import com.jmip.dto.assistant.AssistantEntities;
import com.jmip.dto.assistant.ConversationContext;
import com.jmip.testsupport.ScriptedAiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parsing what a model returns.
 *
 * <p>The cases worth covering here are the malformed ones. A provider that returns clean
 * JSON needs no defending against; one that wraps it in a code fence, trails a sentence
 * after it, or returns an apology instead is the normal condition, and each has to fail
 * safely rather than loudly.
 */
class IntentExtractorTest {

    private final PromptLibrary prompts = new PromptLibrary();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private IntentExtractor extractorFor(ScriptedAiClient client) {
        return new IntentExtractor(client, prompts, objectMapper);
    }

    @Test
    @DisplayName("a well-formed reply parses into intent and entities")
    void parsesValidReply() {
        Optional<IntentExtraction> result = extractorFor(new ScriptedAiClient()
                .respondingWithIntent("""
                        {"intent":"SKILL_DEMAND","entities":{"jobCategory":"Java Developer"},\
                        "timeRange":null,"limit":5}"""))
                .extract("top skills for java developers", ConversationContext.empty());

        assertThat(result).isPresent();
        assertThat(result.get().intent()).isEqualTo("SKILL_DEMAND");
        assertThat(result.get().entitiesOrEmpty().jobCategory()).isEqualTo("Java Developer");
        assertThat(result.get().limit()).isEqualTo(5);
    }

    @Test
    @DisplayName("an intent name outside the enum parses, and is left for the validator")
    void parsesUnknownIntentName() {
        // Parsing and validating are separate jobs. The extractor's contract is "did the
        // model return JSON", not "is the JSON acceptable" — conflating them would hide
        // which of the two went wrong.
        Optional<IntentExtraction> result = extractorFor(new ScriptedAiClient()
                .respondingWithIntent("""
                        {"intent":"DROP_TABLE_JOBS","entities":{},"timeRange":null,"limit":null}"""))
                .extract("anything", ConversationContext.empty());

        assertThat(result).isPresent();
        assertThat(AssistantIntent.parse(result.get().intent())).isEmpty();
    }

    @Test
    @DisplayName("JSON wrapped in a markdown code fence is still read")
    void toleratesCodeFences() {
        Optional<IntentExtraction> result = extractorFor(new ScriptedAiClient()
                .respondingWithIntent("""
                        Here you go:
                        ```json
                        {"intent":"COMPANY_DEMAND","entities":{},"timeRange":null,"limit":null}
                        ```"""))
                .extract("who is hiring", ConversationContext.empty());

        assertThat(result).isPresent();
        assertThat(result.get().intent()).isEqualTo("COMPANY_DEMAND");
    }

    @Test
    @DisplayName("malformed JSON yields nothing rather than a partial intent")
    void rejectsMalformedJson() {
        Optional<IntentExtraction> result = extractorFor(new ScriptedAiClient()
                .respondingWithIntent("{\"intent\":\"SKILL_DEMAND\",\"entities\":"))
                .extract("anything", ConversationContext.empty());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("a reply with no JSON at all yields nothing")
    void rejectsProse() {
        Optional<IntentExtraction> result = extractorFor(new ScriptedAiClient()
                .respondingWithIntent("I'm sorry, I can't help with that."))
                .extract("anything", ConversationContext.empty());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("unknown fields in the reply are ignored rather than fatal")
    void ignoresUnknownFields() {
        // A model adding a field it was not asked for is not a reason to fail a question.
        Optional<IntentExtraction> result = extractorFor(new ScriptedAiClient()
                .respondingWithIntent("""
                        {"intent":"LOCATION_DEMAND","entities":{},"confidence":0.9,\
                        "sql":"select * from jobs","limit":null}"""))
                .extract("which cities", ConversationContext.empty());

        assertThat(result).isPresent();
        assertThat(result.get().intent()).isEqualTo("LOCATION_DEMAND");
    }

    @Test
    @DisplayName("the question is sent as data, under its own heading")
    void sendsQuestionAsLabelledData() {
        ScriptedAiClient client = new ScriptedAiClient();
        extractorFor(client).extract("top skills", ConversationContext.empty());

        assertThat(client.lastRequest().task()).isEqualTo(AiTask.INTENT_EXTRACTION);
        assertThat(client.lastRequest().user()).contains("QUESTION:").contains("top skills");
        assertThat(client.lastRequest().system()).isEqualTo(prompts.intentExtraction());
    }

    @Test
    @DisplayName("the previous turn is sent, labelled as context rather than as a question")
    void sendsPreviousTurnAsContext() {
        ScriptedAiClient client = new ScriptedAiClient();
        ConversationContext context = new ConversationContext(
                "top skills for java developers",
                AssistantIntent.SKILL_DEMAND,
                new AssistantEntities(null, null, "Java Developer", null, null, null, null));

        extractorFor(client).extract("what about Bengaluru?", context);

        String sent = client.lastRequest().user();
        assertThat(sent).contains("PREVIOUS TURN");
        assertThat(sent).contains("Java Developer");
        assertThat(sent).contains("what about Bengaluru?");
    }

    @Test
    @DisplayName("no previous turn means no context section")
    void omitsEmptyContext() {
        ScriptedAiClient client = new ScriptedAiClient();
        extractorFor(client).extract("top skills", ConversationContext.empty());

        assertThat(client.lastRequest().user()).doesNotContain("PREVIOUS TURN");
    }
}
