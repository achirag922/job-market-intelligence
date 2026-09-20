package com.jmip.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The keyword provider used for tests and for running without a key.
 *
 * <p>It is a test double, but it has one property worth pinning: it must answer the
 * question it was asked, not the one before it.
 */
class StubAiClientTest {

    private final StubAiClient client = new StubAiClient();

    private String intentFor(String userMessage) {
        return client.complete(new AiCompletionRequest(
                AiTask.INTENT_EXTRACTION, "system", userMessage, 1024));
    }

    @Test
    @DisplayName("the question alone decides the intent, not the previous turn")
    void ignoresContextWhenChoosingIntent() {
        // Found in the browser: after "which cities...", every later question kept
        // answering about cities, because the remembered question was being matched as
        // though it were the new one. The context is background and must not compete.
        String message = PromptMarkers.PREVIOUS_TURN + "\n"
                + "intent: LOCATION_DEMAND\n"
                + "question: Which cities have the most Java jobs?\n\n"
                + PromptMarkers.QUESTION + "\n"
                + "How are jobs split across categories?";

        assertThat(intentFor(message)).contains("JOB_CATEGORY_DEMAND");
    }

    @Test
    @DisplayName("a message with no headings is still matched")
    void handlesUnmarkedMessage() {
        assertThat(intentFor("which companies are hiring")).contains("COMPANY_DEMAND");
    }

    @Test
    @DisplayName("an unrecognisable question falls back to the general intent")
    void fallsBackToGeneral() {
        assertThat(intentFor(PromptMarkers.QUESTION + "\nwhat is the meaning of life"))
                .contains("GENERAL_JOB_MARKET");
    }

    @Test
    @DisplayName("the reply is the JSON shape the extractor parses")
    void returnsExpectedShape() {
        assertThat(intentFor(PromptMarkers.QUESTION + "\ntop skills"))
                .startsWith("{").contains("\"intent\"").contains("\"entities\"").endsWith("}");
    }

    @Test
    @DisplayName("an empty result set is described as such, with nothing invented")
    void describesEmptyResults() {
        String answer = client.complete(new AiCompletionRequest(
                AiTask.ANSWER_GENERATION, "system", "{\"rowCount\":0,\"rows\": []}", 1024));

        assertThat(answer).contains("does not contain matching data");
    }

    @Test
    @DisplayName("the stub is always available; it needs no credential")
    void isAlwaysAvailable() {
        assertThat(client.isAvailable()).isTrue();
        assertThat(client.providerName()).isEqualTo("stub");
    }
}
