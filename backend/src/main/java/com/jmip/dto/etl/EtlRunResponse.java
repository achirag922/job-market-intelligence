package com.jmip.dto.etl;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * One ETL run, read from the Spring Batch job repository.
 *
 * <p>Status, times and the read and rejected counts are Spring Batch's own records. Only
 * {@code recordsLoaded} and {@code duplicates} come from {@code etl_run_metrics}, because
 * the framework cannot know them; both are null for a run still in progress or one that
 * finished before V6.5 started recording them.
 *
 * @param executionId      Spring Batch job execution id
 * @param jobName          which ETL job ran
 * @param outcome          DERIVED — the Spring Batch status collapsed into what a dashboard shows
 * @param batchStatus      Spring Batch's own status, for detail
 * @param exitCode         Spring Batch exit code
 * @param exitMessage      Spring Batch exit description, usually a stack trace on failure
 * @param startTime        when the run started, in the ETL server's local time
 * @param endTime          when it finished, null while running
 * @param durationMillis   DERIVED — end minus start, or elapsed so far while running
 * @param recordsRead      RAW COUNT — items read across all steps
 * @param recordsProcessed DERIVED — read minus rejected, as the ETL's own summary reports it
 * @param recordsWritten   RAW COUNT — items handed to the writer (loaded plus duplicates)
 * @param recordsLoaded    RAW COUNT — postings actually inserted or rewritten
 * @param duplicates       RAW COUNT — postings skipped as already loaded
 * @param rejected         RAW COUNT — read, process and write skips: records rejected by validation
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EtlRunResponse(
        long executionId,
        String jobName,
        EtlRunOutcome outcome,
        String batchStatus,
        String exitCode,
        String exitMessage,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Long durationMillis,
        long recordsRead,
        long recordsProcessed,
        long recordsWritten,
        Long recordsLoaded,
        Long duplicates,
        long rejected) {
}
