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
        Long duplicates) {
}
