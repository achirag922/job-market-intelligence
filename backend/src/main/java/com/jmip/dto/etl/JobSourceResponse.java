package com.jmip.dto.etl;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.util.List;

/**
 * V8.1: a job source as the monitoring pages see it. Safe metadata only: no file paths,
 * credentials or ingestion settings.
 *
 * @param code        the label the source's records carry (jobs.source)
 * @param jobCount    postings in JMIP from this source
 * @param recentRuns  the latest ETL runs that met this source; only on the detail view
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobSourceResponse(
        long id,
        String code,
        String name,
        String sourceType,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime lastIngestedAt,
        Long lastRunExecutionId,
        long jobCount,
        List<Run> recentRuns) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Run(long executionId, String batchStatus, LocalDateTime startTime, LocalDateTime endTime,
                      String feedName, long recordsLoaded, long recordsSeenAgain) {
    }

    public JobSourceResponse withRecentRuns(List<Run> runs) {
        return new JobSourceResponse(id, code, name, sourceType, active, createdAt, lastIngestedAt, lastRunExecutionId,
                jobCount, runs);
    }
}
