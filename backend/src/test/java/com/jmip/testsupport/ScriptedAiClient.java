package com.jmip.testsupport;

import com.jmip.ai.AiClient;
import com.jmip.ai.AiCompletionRequest;
import com.jmip.ai.AiFailureException;
import com.jmip.ai.AiTask;
import com.jmip.ai.AiUnavailableException;

import java.util.ArrayList;
import java.util.List;

/**
 * An {@link AiClient} that returns exactly what a test tells it to.
 *
 * <p>Tests must not call a real provider: it would make them non-deterministic, slow,
 * dependent on a credential nobody should need to run {@code mvn test}, and it would bill
 * someone for running the suite. This stands in at the one interface the application uses,
 * so everything above it — parsing, validation, routing, grounding, failure handling — runs
 * exactly as it does in production.
 *
 * <p>Scripting the reply is also the only way to test the cases that matter most. A real
 * provider cannot be asked to return malformed JSON, or an intent that does not exist, or
 * to time out on demand.
 */
public class ScriptedAiClient implements AiClient {

    private String intentReply = """
            {"intent":"GENERAL_JOB_MARKET","entities":{},"timeRange":null,"limit":null}""";
    private String answerReply = "A description of the retrieved rows.";
    private RuntimeException intentFailure;
    private RuntimeException answerFailure;
    private boolean available = true;

    /** Every request this client was given, so a test can assert what the model was sent. */
    private final List<AiCompletionRequest> requests = new ArrayList<>();

    public ScriptedAiClient respondingWithIntent(String json) {
        this.intentReply = json;
        return this;
    }

    public ScriptedAiClient respondingWithAnswer(String text) {
        this.answerReply = text;
        return this;
    }

    /** The provider fails when asked to extract an intent. */
    public ScriptedAiClient failingIntentWith(RuntimeException failure) {
        this.intentFailure = failure;
        return this;
    }

    /** The provider fails only at the second call, after rows have been retrieved. */
    public ScriptedAiClient failingAnswerWith(RuntimeException failure) {
        this.answerFailure = failure;
        return this;
    }

    /** No credential configured. */
    public ScriptedAiClient unavailable() {
        this.available = false;
        return this;
    }

    public List<AiCompletionRequest> requests() {
        return List.copyOf(requests);
    }

    public AiCompletionRequest lastRequest() {
        return requests.get(requests.size() - 1);
    }

    @Override
    public String providerName() {
        return "scripted";
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String complete(AiCompletionRequest request) {
        requests.add(request);
        if (!available) {
            throw new AiUnavailableException("No AI API key is configured");
        }
        if (request.task() == AiTask.INTENT_EXTRACTION) {
            if (intentFailure != null) {
                throw intentFailure;
            }
            return intentReply;
        }
        if (answerFailure != null) {
            throw answerFailure;
        }
        return answerReply;
    }

    /** A provider timeout, as the client surfaces it. */
    public static AiFailureException timeout() {
        return new AiFailureException("AI provider call failed",
                new java.net.SocketTimeoutException("timeout"));
    }

    /** A rate-limit rejection, as the client surfaces it. */
    public static AiFailureException rateLimited() {
        return new AiFailureException("AI provider call failed",
                new IllegalStateException("429 rate_limit_error"));
    }
}
