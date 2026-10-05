package com.jmip.dto.learning;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** V9.5: the learning plan's requests and responses. The owner always comes from the session. */
public final class LearningDtos {

    private LearningDtos() {
    }

    /**
     * A new item: for a known skill ({@code skillId}, e.g. a roadmap priority) or one typed in.
     *
     * @param priority HIGH, MEDIUM or LOW; absent means the roadmap's ranking decides (MEDIUM off the roadmap)
     */
    public record ItemRequest(
            Long skillId,
            @Size(max = 100) String skillName,
            @NotBlank(message = "a learning topic is required") @Size(max = 200) String topic,
            @Pattern(regexp = "HIGH|MEDIUM|LOW", message = "must be HIGH, MEDIUM or LOW") String priority,
            LocalDate targetDate,
            @Size(max = 2000) String notes) {
    }

    /** Replaces the editable fields; the status has its own endpoint. */
    public record ItemUpdate(
            @NotBlank(message = "a learning topic is required") @Size(max = 200) String topic,
            @NotNull @Pattern(regexp = "HIGH|MEDIUM|LOW", message = "must be HIGH, MEDIUM or LOW") String priority,
            @NotNull @Min(0) @Max(100) Integer progress,
            LocalDate targetDate,
            @Size(max = 2000) String notes) {
    }

    public record StatusRequest(
            @NotNull @Pattern(regexp = "NOT_STARTED|IN_PROGRESS|COMPLETED",
                    message = "must be NOT_STARTED, IN_PROGRESS or COMPLETED") String status) {
    }

    public record ResourceRequest(
            @NotBlank(message = "a title is required") @Size(max = 200) String title,
            @NotBlank(message = "a URL is required") @Size(max = 500)
            @Pattern(regexp = "(?i)^https?://\\S+$", message = "must be an http or https address") String url,
            @NotNull @Pattern(regexp = "COURSE|VIDEO|ARTICLE|DOCUMENTATION|PROJECT|OTHER",
                    message = "must be COURSE, VIDEO, ARTICLE, DOCUMENTATION, PROJECT or OTHER") String type,
            @Size(max = 1000) String notes) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Resource(UUID id, String title, String url, String type, String notes, OffsetDateTime createdAt) {
    }

    /**
     * @param roadmapStatus the status of this skill on the career goal's roadmap, when the item serves one
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(UUID id, UUID goalId, Long skillId, String skill, String topic, String priority, String status,
                       int progress, LocalDate targetDate, String notes, OffsetDateTime createdAt,
                       OffsetDateTime updatedAt, OffsetDateTime startedAt, OffsetDateTime completedAt,
                       String roadmapStatus, List<Resource> resources) {
    }

    /** A roadmap skill still to develop (V7.4 ranking), and the item already created for it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Priority(int rank, Long skillId, String skill, String reason, String roadmapStatus,
                           String suggestedPriority, UUID itemId) {
    }

    /** @param averageProgress the mean of the items' progress, absent without items */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Progress(int items, int notStarted, int inProgress, int completed, Double averageProgress,
                           List<String> completedSkills) {
    }

    /** A completed skill some of the user's saved jobs ask for. */
    public record SkillDemand(String skill, int savedJobs) {
    }

    /**
     * What learning has changed for the career goal, the resume and job matches. Read-only: the resume
     * is never edited here, and matches use the resume, so a completed skill counts once it is on it.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Impact(String goalRole, Integer roadmapSkills, Integer roadmapCompleted, Double roadmapPercentComplete,
                         List<String> completedNotOnResume, List<SkillDemand> savedJobDemand, String note) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Plan(UUID goalId, String goalRole, List<Priority> priorities, List<Item> items, Progress progress,
                       Impact impact, String note) {
    }
}
