package com.jmip.service;

import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.dto.PagedResponse;
import com.jmip.dto.etl.EtlRunOutcome;
import com.jmip.dto.etl.EtlRunResponse;
import com.jmip.repository.EtlRunRepository;
import com.jmip.repository.projection.EtlRunRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EtlRunServiceTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 24, 10, 0, 0);
    private static final ZoneId ZONE = ZoneId.of("UTC");

    @Mock
    private EtlRunRepository repository;

    private EtlRunService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(START.plusSeconds(90).atZone(ZONE).toInstant(), ZONE);
        service = new EtlRunService(repository, clock);
    }

    @Test
    @DisplayName("maps Spring Batch counters and the ETL's own counters onto one run")
    void mapsCounts() {
        EtlRunResponse run = service.toResponse(row(7, "COMPLETED", START.plusSeconds(12), 10, 7, 3, 5L, 2L));

        assertThat(run.executionId()).isEqualTo(7);
        assertThat(run.outcome()).isEqualTo(EtlRunOutcome.SUCCEEDED);
        assertThat(run.recordsRead()).isEqualTo(10);
        assertThat(run.recordsProcessed()).as("read minus rejected").isEqualTo(7);
        assertThat(run.recordsWritten()).isEqualTo(7);
        assertThat(run.recordsLoaded()).isEqualTo(5);
        assertThat(run.duplicates()).isEqualTo(2);
        assertThat(run.rejected()).isEqualTo(3);
        assertThat(run.durationMillis()).isEqualTo(12_000);
    }

    @ParameterizedTest
    @CsvSource({
            "COMPLETED, SUCCEEDED",
            "FAILED, FAILED",
            "STARTING, RUNNING",
            "STARTED, RUNNING",
            "STOPPING, RUNNING",
            "STOPPED, STOPPED",
            "ABANDONED, STOPPED",
            "UNKNOWN, STOPPED"})
    @DisplayName("collapses every Spring Batch status into a dashboard outcome")
    void mapsStatus(String batchStatus, EtlRunOutcome expected) {
        assertThat(EtlRunOutcome.fromBatchStatus(batchStatus)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a running job reports time elapsed so far and leaves unknown counters empty")
    void runningJob() {
        EtlRunResponse run = service.toResponse(row(8, "STARTED", null, 4, 0, 0, null, null));

        assertThat(run.outcome()).isEqualTo(EtlRunOutcome.RUNNING);
        assertThat(run.endTime()).isNull();
        assertThat(run.durationMillis()).isEqualTo(90_000);
        assertThat(run.recordsLoaded()).isNull();
        assertThat(run.duplicates()).isNull();
    }

    @Test
    @DisplayName("a run that stopped without an end time has no duration rather than a growing one")
    void stoppedWithoutEnd() {
        assertThat(service.toResponse(row(9, "FAILED", null, 1, 0, 0, null, null)).durationMillis()).isNull();
    }

    @Test
    @DisplayName("pages newest-first history with the database total")
    void pagesHistory() {
        when(repository.countRuns(null)).thenReturn(12L);
        when(repository.findRuns(null, 5, 5L)).thenReturn(List.of(row(7, "COMPLETED", START, 1, 1, 0, 1L, 0L)));

        PagedResponse<EtlRunResponse> page = service.runs("  ", 1, 5);

        assertThat(page.content()).extracting(EtlRunResponse::executionId).containsExactly(7L);
        assertThat(page.totalElements()).isEqualTo(12);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.page()).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty history is an empty page, without querying for rows")
    void emptyHistory() {
        when(repository.countRuns("ingestJobPostings")).thenReturn(0L);

        PagedResponse<EtlRunResponse> page = service.runs("ingestJobPostings", 0, 10);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
        verify(repository, never()).findRuns(any(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("latest is the newest run, and a 404 when nothing has run")
    void latest() {
        when(repository.findRuns(null, 1, 0)).thenReturn(List.of(row(7, "FAILED", START, 1, 0, 1, 0L, 0L)))
                .thenReturn(List.of());

        assertThat(service.latest(null).outcome()).isEqualTo(EtlRunOutcome.FAILED);
        assertThatThrownBy(() -> service.latest(null)).isInstanceOf(ResourceNotFoundException.class);
    }

    private static EtlRunRow row(long id, String status, LocalDateTime end, long read, long written, long skipped,
                                 Long loaded, Long duplicates) {
        return new EtlRunRow(id, "ingestJobPostings", status, status, "", START, end, read, written, skipped,
                loaded, duplicates);
    }
}
