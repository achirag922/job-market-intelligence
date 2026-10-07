package com.jmip.config;

import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.dto.etl.EtlRunOutcome;
import com.jmip.dto.etl.EtlRunResponse;
import com.jmip.service.EtlRunService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** V10.6: the latest ETL run as gauges. */
class EtlMetricsTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final EtlRunService runs = mock(EtlRunService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private static EtlRunResponse run(EtlRunOutcome outcome, LocalDateTime end) {
        EtlRunResponse run = mock(EtlRunResponse.class);
        when(run.outcome()).thenReturn(outcome);
        when(run.startTime()).thenReturn(LocalDateTime.of(2026, 10, 7, 9, 0));
        when(run.endTime()).thenReturn(end);
        when(run.durationMillis()).thenReturn(90_000L);
        when(run.recordsWritten()).thenReturn(120L);
        when(run.rejected()).thenReturn(3L);
        return run;
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    @Test
    @DisplayName("a successful run publishes success, age, duration and record counts")
    void successfulRun() {
        EtlRunResponse succeeded = run(EtlRunOutcome.SUCCEEDED, LocalDateTime.of(2026, 10, 7, 11, 0));
        when(runs.latest(any())).thenReturn(succeeded);
        new EtlMetrics(runs, registry, clock);

        assertThat(gauge("jmip.etl.last.run.success")).isEqualTo(1);
        assertThat(gauge("jmip.etl.last.run.age")).isEqualTo(3600);
        assertThat(gauge("jmip.etl.last.run.duration")).isEqualTo(90);
        assertThat(gauge("jmip.etl.last.run.records.written")).isEqualTo(120);
        assertThat(gauge("jmip.etl.last.run.records.rejected")).isEqualTo(3);
    }

    @Test
    @DisplayName("a failed run reports 0; no run yet reports NaN")
    void failedOrMissing() {
        EtlRunResponse failed = run(EtlRunOutcome.FAILED, LocalDateTime.of(2026, 10, 7, 11, 0));
        when(runs.latest(any())).thenReturn(failed);
        new EtlMetrics(runs, registry, clock);
        assertThat(gauge("jmip.etl.last.run.success")).isZero();

        SimpleMeterRegistry empty = new SimpleMeterRegistry();
        EtlRunService none = mock(EtlRunService.class);
        when(none.latest(any())).thenThrow(new ResourceNotFoundException("No ETL run has been recorded yet"));
        new EtlMetrics(none, empty, clock);
        assertThat(empty.get("jmip.etl.last.run.success").gauge().value()).isNaN();
    }

    @Test
    @DisplayName("the database is read at most once a minute, and an outage keeps the last known run")
    void cachedAndResilient() {
        EtlRunResponse succeeded = run(EtlRunOutcome.SUCCEEDED, LocalDateTime.of(2026, 10, 7, 11, 0));
        when(runs.latest(any())).thenReturn(succeeded);
        EtlMetrics metrics = new EtlMetrics(runs, registry, clock);
        gauge("jmip.etl.last.run.success");
        gauge("jmip.etl.last.run.age");
        verify(runs, times(1)).latest(any());

        EtlRunService failing = mock(EtlRunService.class);
        when(failing.latest(any())).thenThrow(new DataAccessResourceFailureException("down"));
        SimpleMeterRegistry other = new SimpleMeterRegistry();
        new EtlMetrics(failing, other, clock);
        assertThat(other.get("jmip.etl.last.run.success").gauge().value()).isNaN();
        assertThat(metrics.current()).isNotNull();
    }
}
