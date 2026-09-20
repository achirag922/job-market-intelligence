package com.jmip.ai;

/**
 * One model call: fixed instructions, then the material to work on.
 *
 * <p>Split in two because the parts have different trust levels. {@code system} is written
 * by this application; {@code user} carries the question and the retrieved rows. Keeping
 * them apart is what lets a provider mark the boundary between instruction and data.
 *
 * @param task      which of the assistant's two calls this is
 * @param system    instructions for the model
 * @param user      the question, or the question plus retrieved data
 * @param maxTokens ceiling for this particular call, which may be lower than the default
 */
public record AiCompletionRequest(AiTask task, String system, String user, int maxTokens) {

    public AiCompletionRequest {
        if (task == null) {
            throw new IllegalArgumentException("task is required");
        }
        if (system == null || system.isBlank()) {
            throw new IllegalArgumentException("system prompt is required");
        }
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("user content is required");
        }
    }
}
