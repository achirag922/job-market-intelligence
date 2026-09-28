package com.jmip.service;

import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.dto.PagedResponse;
import com.jmip.dto.etl.EtlRunOutcome;
import com.jmip.dto.etl.EtlRunResponse;
import com.jmip.repository.EtlRunRepository;
import com.jmip.repository.projection.EtlRunRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * ETL run history for the monitoring dashboard, built on Spring Batch's own records.
 */
@Service
@Transactional(readOnly = true)
public class EtlRunService {

    private final EtlRunRepository etlRunRepository;
    private final Clock clock;

    @Autowired
    public EtlRunService(EtlRunRepository etlRunRepository) {
        this(etlRunRepository, Clock.systemDefaultZone());
    }

    EtlRunService(EtlRunRepository etlRunRepository, Clock clock) {
        this.etlRunRepository = etlRunRepository;
        this.clock = clock;
    }

    /** Newest first. An empty history is an empty page, not an error. */
    public PagedResponse<EtlRunResponse> runs(String jobName, int page, int size) {
        String job = normalise(jobName);
        long total = etlRunRepository.countRuns(job);
        List<EtlRunResponse> content = total == 0
                ? List.of()
                : withSources(etlRunRepository.findRuns(job, size, (long) page * size));
        return PagedResponse.of(new PageImpl<>(content, PageRequest.of(page, size), total));
    }

    /** @throws ResourceNotFoundException when no ETL run has ever been recorded */
    public EtlRunResponse latest(String jobName) {
        return withSources(etlRunRepository.findRuns(normalise(jobName), 1, 0)).stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("No ETL run has been recorded yet"));
    }

    /** V8.1: each run with the sources it met, read in one query for the whole list. */
    private List<EtlRunResponse> withSources(List<EtlRunRow> rows) {
        java.util.Map<Long, List<EtlRunResponse.Source>> byRun = new java.util.HashMap<>();
        for (EtlRunRow.SourceCount count : etlRunRepository.findSources(rows.stream().map(EtlRunRow::executionId).toList())) {
            byRun.computeIfAbsent(count.executionId(), id -> new java.util.ArrayList<>()).add(new EtlRunResponse.Source(
                    count.sourceId(), count.code(), count.name(), count.recordsLoaded(), count.recordsSeenAgain()));
        }
        return rows.stream().map(row -> toResponse(row, byRun.getOrDefault(row.executionId(), List.of()))).toList();
    }

    EtlRunResponse toResponse(EtlRunRow row) {
        return toResponse(row, List.of());
    }

    EtlRunResponse toResponse(EtlRunRow row, List<EtlRunResponse.Source> sources) {
        EtlRunOutcome outcome = EtlRunOutcome.fromBatchStatus(row.status());
        return new EtlRunResponse(
                row.executionId(),
                row.jobName(),
                outcome,
                row.status(),
                row.exitCode(),
                row.exitMessage() == null || row.exitMessage().isBlank() ? null : row.exitMessage(),
                row.startTime(),
                row.endTime(),
                durationMillis(row, outcome),
                row.readCount(),
                // The same arithmetic as the ETL's closing summary, so the two always agree.
                Math.max(0, row.readCount() - row.skipCount()),
                row.writeCount(),
                row.recordsLoaded(),
                row.duplicates(),
                row.skipCount(),
                row.feedName(),
                row.feedType(),
                sources);
    }

    /**
     * A running job has no end yet, so its duration is the time elapsed so far. A job that
     * stopped without an end time, such as one killed with the JVM, has no duration.
     */
    private Long durationMillis(EtlRunRow row, EtlRunOutcome outcome) {
        if (row.startTime() == null) {
            return null;
        }
        LocalDateTime end = row.endTime();
        if (end == null) {
            if (outcome != EtlRunOutcome.RUNNING) {
                return null;
            }
            end = LocalDateTime.now(clock);
        }
        return Math.max(0, Duration.between(row.startTime(), end).toMillis());
    }

    private static String normalise(String jobName) {
        return jobName == null || jobName.isBlank() ? null : jobName.trim();
    }
}
