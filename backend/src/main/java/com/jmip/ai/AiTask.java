package com.jmip.ai;

/**
 * Which of the assistant's two model calls this is.
 *
 * <p>Providers are free to ignore it — the real one does — but it keeps logs readable when
 * a question makes two calls, and it lets a test double answer each call differently
 * without inspecting prompt text.
 */
public enum AiTask {

    /** Natural-language question in, structured intent JSON out. */
    INTENT_EXTRACTION,

    /** Retrieved rows in, a sentence describing them out. */
    ANSWER_GENERATION
}
