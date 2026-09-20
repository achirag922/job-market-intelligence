package com.jmip.ai;

/**
 * No model provider is configured, so the assistant cannot answer at all.
 *
 * <p>Distinct from {@link AiFailureException}: this one is a deployment state that will
 * not fix itself on a retry, and the user is told so rather than invited to try again.
 */
public class AiUnavailableException extends RuntimeException {

    public AiUnavailableException(String message) {
        super(message);
    }
}
