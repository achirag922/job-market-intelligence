package com.jmip.dto.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jmip.entity.ApplicationStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V8.5: facts about the signed-in user's own applications, computed from what they tracked.
 * "Applications" are tracked jobs that left SAVED. A figure that needs more data than there is
 * is absent, with a note saying what it needs; nothing is estimated.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplicationInsightsResponse(
        long tracked,
        long applications,
        Map<ApplicationStatus, Long> statusCounts,
        Funnel funnel,
        List<MonthActivity> activity,
        String activityNote,
        Double averageMatchPercentage,
        int scoredApplications,
        String matchNote,
        List<Count> topMissingSkills,
        List<Count> topCompanies,
        List<Count> topRoles,
        List<FollowUp> upcomingFollowUps,
        List<FollowUp> overdueFollowUps) {

    /**
     * Of the current applications, how many ever reached an interview and an offer. Rates are
     * shares of applications, present only from {@code minimumForRates} applications.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Funnel(long applied, long interviewed, long offers, Double interviewRate, Double offerRate,
                         int minimumForRates, String note) {
    }

    /** One calendar month (UTC): jobs saved, applications made, and interviews and offers reached. */
    public record MonthActivity(String month, long saved, long applied, long interviews, long offers) {
    }

    public record Count(String name, long count) {
    }

    public record FollowUp(UUID id, long jobId, String jobTitle, String companyName, ApplicationStatus status,
                           LocalDate followUpOn, String note) {
    }
}
