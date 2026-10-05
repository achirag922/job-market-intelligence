package com.jmip.repository.projection;

import java.time.LocalDateTime;

/**
 * One Spring Batch job execution with its step counters summed, and the V6.5 counters
 * when they were recorded.
 */
public record EtlRunRow(
        long executionId,
        String jobName,
        String status,
        String exitCode,
        String exitMessage,
        LocalDateTime startTime,
        LocalDateTime endTime,
        long readCount,
        long writeCount,
        long skipCount,
        Long recordsLoaded,
        Long duplicates,
        // V8.1: the feed the run read (file name only) and its format.
        String feedName,
        String feedType,
        // V8.2: jobs the run marked expired or closed; null for runs recorded before V8.2 metrics.
        Long expired,
        // V9.1: the connector the run read through; null for runs recorded before V9.1 without a file feed.
        String connector) {

    public EtlRunRow(long executionId, String jobName, String status, String exitCode, String exitMessage,
                     LocalDateTime startTime, LocalDateTime endTime, long readCount, long writeCount, long skipCount,
                     Long recordsLoaded, Long duplicates) {
        this(executionId, jobName, status, exitCode, exitMessage, startTime, endTime, readCount, writeCount, skipCount,
                recordsLoaded, duplicates, null, null, null, null);
    }

    /** What one run did with one source (V8.1). */
    public record SourceCount(long executionId, long sourceId, String code, String name, long recordsLoaded,
                              long recordsSeenAgain) {
    }
}
