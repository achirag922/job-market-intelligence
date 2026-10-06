package com.jmip.dto.interview;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

    /**
     * @param resumeId      one of your processed resumes; absent means your current one, if any
     * @param interviewType V9.6: TECHNICAL, BEHAVIORAL or MIXED (the default)
     * @param difficulty    V9.6: EASY, MEDIUM (the default) or HARD
     * @param questionCount V9.6: 3 to 10; absent keeps the V8.7 set for MIXED and six otherwise
     */
    public record StartRequest(
            @NotNull @Positive Long jobId,
            UUID resumeId,
            @Pattern(regexp = "TECHNICAL|BEHAVIORAL|MIXED", message = "must be TECHNICAL, BEHAVIORAL or MIXED") String interviewType,
            @Pattern(regexp = "EASY|MEDIUM|HARD", message = "must be EASY, MEDIUM or HARD") String difficulty,
            @Min(value = 3, message = "ask for at least 3 questions") @Max(value = 10, message = "ask for at most 10 questions")
            Integer questionCount) {
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
                                  List<QuestionResponse> questions, String interviewType, String difficulty, int skipped,
                                  Report report) {
    }

    /**
     * V9.6: the end-of-interview report, computed from the stored feedback. Scores are 1 to 5 and
     * absent when nothing in that group was evaluated.
     *
     * @param prepareTopics skills and topics from weak or skipped questions
     * @param learning      matching items or priority skills from the V9.5 learning plan
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Report(Double overallScore, Double technicalScore, Double behavioralScore, List<String> strongAreas,
                         List<String> weakAreas, List<String> prepareTopics, List<LearningSuggestion> learning) {
    }

    /** @param source PLAN_ITEM (already in your learning plan) or ROADMAP_PRIORITY (a priority skill to plan) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LearningSuggestion(String skill, String source, UUID itemId, Long skillId, String status, String reason) {
    }

    /**
     * @param feedbackStatus NOT_ANSWERED, EVALUATED, UNAVAILABLE when the AI could not evaluate it, or SKIPPED (V9.6)
     * @param feedbackNote   why feedback is missing, when it is
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuestionResponse(int position, String category, String question, String focus, String answer,
                                   OffsetDateTime answeredAt, String feedbackStatus, Feedback feedback,
                                   String feedbackNote, int evaluationAttempts, OffsetDateTime skippedAt) {
    }

    /** Scores from 1 to 5; technical correctness only for questions where it applies. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Feedback(int relevance, int completeness, int clarity, Integer technicalCorrectness, double score,
                           List<String> strengths, List<String> improvements, OffsetDateTime evaluatedAt,
                           Integer communication, String suggestedApproach) {
    }
}
