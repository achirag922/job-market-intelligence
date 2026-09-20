package com.jmip.ai;

/**
 * The one way this application talks to a language model.
 *
 * <p>Deliberately small. Everything above it — intent extraction, validation, routing,
 * answer generation — is ordinary Java that works against text in and text out, so
 * changing provider means writing one class and changing one property, not touching the
 * assistant. The interface knows nothing about job market concepts, and nothing about any
 * provider's request or response types; provider objects never escape an implementation.
 *
 * <p>It exposes no way to run code, reach a URL of the model's choosing, or query the
 * database. A model can only return text, and every caller validates that text.
 */
public interface AiClient {

    /** Which provider this is, for logging and for the health of the assistant endpoint. */
    String providerName();

    /**
     * Whether a call could succeed right now. False when no credential is configured, so
     * the assistant can say so plainly instead of failing at the first question.
     */
    boolean isAvailable();

    /**
     * @return the model's reply as plain text
     * @throws AiUnavailableException when no provider is configured
     * @throws AiFailureException     on timeout, rate limit, transport or provider error
     */
    String complete(AiCompletionRequest request);
}
