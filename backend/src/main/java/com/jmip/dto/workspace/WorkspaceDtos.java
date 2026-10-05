package com.jmip.dto.workspace;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jmip.dto.JobSummaryResponse;
import com.jmip.dto.PersonalizedFeedResponse;
import com.jmip.dto.saved.SavedJobResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** V9.14: the job-search workspace. The owner always comes from the session. */
public final class WorkspaceDtos {

    private WorkspaceDtos() {
    }

    /** @param filters the Job Explorer's own query parameters (filters and ordering), at most 20 */
    public record SavedSearchRequest(
            @NotBlank(message = "give the search a name") @Size(max = 80) String name,
            @NotNull(message = "filters are required") @Size(max = 20) Map<String, String> filters) {
    }

    public record SavedSearch(UUID id, String name, Map<String, String> filters, OffsetDateTime createdAt) {
    }

    /** @param priority HIGH, MEDIUM, LOW, or absent to clear it */
    public record PriorityRequest(
            @Pattern(regexp = "HIGH|MEDIUM|LOW", message = "must be HIGH, MEDIUM or LOW") String priority) {
    }

    public record ViewedJob(JobSummaryResponse job, OffsetDateTime viewedAt) {
    }

    public record HiddenJob(JobSummaryResponse job, OffsetDateTime hiddenAt) {
    }

    /**
     * Why a job matches the current resume, from the existing skill match.
     *
     * @param matched up to five skills the resume shows
     * @param missing up to five the job asks for that the resume does not show
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MatchHint(Double percentage, List<String> matched, List<String> missing) {
    }

    /** @param note why there are no hints, when there are none */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Matches(Map<Long, MatchHint> matches, String note) {
    }

    /**
     * The whole workspace in one call.
     *
     * @param recommended the V9.2 personalized feed's top jobs (hidden ones left out)
     * @param followUps   saved jobs with a follow-up date, soonest first, closed applications left out
     */
    public record Workspace(List<PersonalizedFeedResponse.Item> recommended, List<SavedJobResponse> saved,
                            List<SavedJobResponse> applied, List<SavedJobResponse> followUps, List<ViewedJob> recentlyViewed,
                            List<HiddenJob> hidden, List<SavedSearch> savedSearches) {
    }
}
