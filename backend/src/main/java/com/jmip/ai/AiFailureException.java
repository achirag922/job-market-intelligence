package com.jmip.ai;

/**
 * A model call was attempted and did not produce a usable reply.
 *
 * <p>Covers timeouts, rate limits, transport failures and provider errors. The message is
 * for logs; callers turn it into a short sentence for the user, because a provider's error
 * text can carry request details that should not be shown.
 */
public class AiFailureException extends RuntimeException {

    public AiFailureException(String message, Throwable cause) {
        super(message, cause);
    }

    public AiFailureException(String message) {
        super(message);
    }
}
