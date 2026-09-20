package com.jmip.dto.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * One question for the assistant.
 *
 * @param resumeId a resume already uploaded through the V3 endpoints. Required only for
 *                 the resume intents, and supplied by the caller rather than inferred, so
 *                 a question can never reach someone else's resume by naming it
 * @param jobId    a posting, for "how well does my resume match this job"
 * @param context  the previous turn, echoed back from the last response
 */
public record AssistantQueryRequest(
        @NotBlank(message = "question is required")
        @Size(max = 2000, message = "question must be at most 2000 characters")
        String question,

        UUID resumeId,

        @Positive(message = "jobId must be positive")
        Long jobId,

        @Valid ConversationContext context) {

    public ConversationContext contextOrEmpty() {
        return context == null ? ConversationContext.empty() : context;
    }
}
