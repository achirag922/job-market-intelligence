package com.jmip.dto.interview;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** V8.7: interview preparation requests and responses. The owner always comes from the session. */
public final class InterviewDtos {

    private InterviewDtos() {
    }

    /** @param resumeId one of your processed resumes; absent means your current one, if any */
    public record StartRequest(@NotNull @Positive Long jobId, UUID resumeId) {
    }

    public record AnswerRequest(
            @NotBlank(message = "write an answer first")
            @Size(max = 4000, message = "answers can be at most 4000 characters") String answer) {
    }

    /**
     * One session. {@code questions} is present on the detail view only.
     *
     * @param averageScore mean of the evaluated answers' scores (1 to 5), set on completion
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionResponse(UUID id, Long jobId, String jobTitle, String companyName, UUID resumeId, String status,
                                  OffsetDateTime createdAt, OffsetDateTime completedAt, String summary,
                                  BigDecimal averageScore, int answered, int evaluated, int total,
                                  List<QuestionResponse> questions) {
    }

    /**
     * @param feedbackStatus NOT_ANSWERED, EVALUATED, or UNAVAILABLE when the AI could not evaluate it
     * @param feedbackNote   why feedback is missing, when it is
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuestionResponse(int position, String category, String question, String focus, String answer,
                                   OffsetDateTime answeredAt, String feedbackStatus, Feedback feedback,
                                   String feedbackNote, int evaluationAttempts) {
    }

    /** Scores from 1 to 5; technical correctness only for questions where it applies. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Feedback(int relevance, int completeness, int clarity, Integer technicalCorrectness, double score,
                           List<String> strengths, List<String> improvements, OffsetDateTime evaluatedAt) {
    }
}
