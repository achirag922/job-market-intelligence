package com.jmip.dto.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jmip.dto.etl.EtlRunResponse;
import com.jmip.dto.etl.JobSourceResponse;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V8.9: what the admin area shows. Counts and account metadata only: never a password or its
 * hash, a session, a verification code, resume contents or a rejected record's raw text.
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Overview(Users users, Jobs jobs, Etl etl, List<JobSourceResponse> sources, Health health,
                           Activity activity) {
    }

    /**
     * @param activeLast30Days accounts that saved, uploaded or changed something in the last 30
     *                         days; sign-ins are not recorded, so this is the closest measure
     */
    public record Users(long total, long verified, long admins, long newLast30Days, long activeLast30Days,
                        String activeDefinition) {
    }

    public record Jobs(long total, long active, long inactive, long firstSeenLast7Days, long firstSeenLast30Days,
                       LocalDate latestPostedDate) {
    }

    /** Spring Batch's own record of the ETL, through the V6.5 monitoring service. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Etl(EtlRunResponse latest, List<EtlRunResponse> recent, long failedLast30Days) {
    }

    /** Actuator health: statuses only, no details. */
    public record Health(String status, Map<String, String> components) {
    }

    /** What happened on the platform in the last {@code days} days, counted, never per person. */
    public record Activity(int days, long signups, long resumesUploaded, long jobsSaved, long applications,
                           long interviewSessions, long jobsEmailedInAlerts) {
    }

    public record DataQuality(Totals totals, Current current, List<ReasonCount> topRejectionReasons,
                              List<SourceQuality> sources, List<String> notes) {
    }

    /** Over every recorded ingestion run (V8.2 metrics). */
    public record Totals(long ingestionRuns, long recordsRead, long validRecords, long rejected, long loaded,
                         long duplicates, long expired) {
    }

    /** The jobs table now. */
    public record Current(long jobs, long active, long inactive, long expiredByDate, long closedBySource) {
    }

    public record ReasonCount(String reason, long count) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SourceQuality(long sourceId, String code, String name, String sourceType, boolean active,
                                OffsetDateTime lastIngestedAt, long jobs, long activeJobs, long inactiveJobs,
                                long loaded, long seenAgain) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserSummary(UUID id, String email, String fullName, String role, boolean emailVerified,
                              OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    /** One account's metadata and how much it has stored; never the stored content itself. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserDetail(UserSummary account, long resumes, long savedJobs, long applications, long jobAlerts,
                             long interviewSessions, long careerGoals, OffsetDateTime lastActivityAt, String note) {
    }

    public record SourceStatusRequest(@NotNull(message = "active is required") Boolean active) {
    }
}
