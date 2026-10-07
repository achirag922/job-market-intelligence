package com.jmip.config;

import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.dto.etl.EtlRunResponse;
import com.jmip.service.EtlRunService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.function.ToDoubleFunction;

/**
 * V10.6: ETL health as metrics. The ETL is a short-lived batch process with nothing to scrape, so the
 * backend publishes its latest run from the Spring Batch tables under /actuator/metrics:
 *
 * <ul>
 *   <li>{@code jmip.etl.last.run.success}: 1 succeeded, 0 failed or stopped (NaN while running or before any run)</li>
 *   <li>{@code jmip.etl.last.run.age}: seconds since the run ended (or started, while running)</li>
 *   <li>{@code jmip.etl.last.run.duration}: seconds the run took</li>
 *   <li>{@code jmip.etl.last.run.records.written} and {@code jmip.etl.last.run.records.rejected}</li>
 * </ul>
 *
 * Alert on a failed run or on an age beyond the expected schedule. The database is read at most once a
 * minute, and a database problem never fails a metrics scrape.
 */
@Component
public class EtlMetrics {

    private static final Logger log = LoggerFactory.getLogger(EtlMetrics.class);
    static final Duration REFRESH = Duration.ofMinutes(1);

    private final EtlRunService runs;
    private final Clock clock;
    private volatile EtlRunResponse latest;
    private volatile Instant readAt;

    @Autowired
    public EtlMetrics(EtlRunService runs, MeterRegistry registry) {
        this(runs, registry, Clock.systemDefaultZone());
    }

    EtlMetrics(EtlRunService runs, MeterRegistry registry, Clock clock) {
        this.runs = runs;
        this.clock = clock;
        gauge(registry, "jmip.etl.last.run.success", null, "1 when the latest ETL run succeeded, 0 when it failed or stopped",
                run -> switch (run.outcome()) {
                    case SUCCEEDED -> 1;
                    case FAILED, STOPPED -> 0;
                    case RUNNING -> Double.NaN;
                });
        gauge(registry, "jmip.etl.last.run.age", "seconds", "Seconds since the latest ETL run ended (or started, while running)",
                run -> secondsSince(run.endTime() != null ? run.endTime() : run.startTime()));
        gauge(registry, "jmip.etl.last.run.duration", "seconds", "How long the latest ETL run took",
                run -> run.durationMillis() == null ? Double.NaN : run.durationMillis() / 1000.0);
        gauge(registry, "jmip.etl.last.run.records.written", "records", "Records written by the latest ETL run",
                run -> run.recordsWritten());
        gauge(registry, "jmip.etl.last.run.records.rejected", "records", "Records rejected by the latest ETL run",
                run -> run.rejected());
    }

    private void gauge(MeterRegistry registry, String name, String unit, String description, ToDoubleFunction<EtlRunResponse> value) {
        Gauge.builder(name, this, metrics -> {
                    EtlRunResponse run = metrics.current();
                    return run == null ? Double.NaN : value.applyAsDouble(run);
                })
                .description(description)
                .baseUnit(unit)
                .strongReference(true)
                .register(registry);
    }

    /** The latest run, re-read when the cached one is older than {@link #REFRESH}. */
    EtlRunResponse current() {
        Instant now = clock.instant();
        if (readAt == null || Duration.between(readAt, now).compareTo(REFRESH) >= 0) {
            try {
                latest = runs.latest(null);
            } catch (ResourceNotFoundException none) {
                latest = null;
            } catch (DataAccessException unavailable) {
                // Keep the last known run; the database health indicator reports the outage.
                log.debug("ETL metrics not refreshed: {}", unavailable.getClass().getSimpleName());
            }
            readAt = now;
        }
        return latest;
    }

    private double secondsSince(LocalDateTime time) {
        if (time == null) {
            return Double.NaN;
        }
        // Spring Batch stores local times; read them in the same zone the backend's clock uses.
        return Math.max(0, Duration.between(time.atZone(clock.getZone()).toInstant(), clock.instant()).toSeconds());
    }
}
