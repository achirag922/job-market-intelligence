package com.jmip.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

/**
 * V9.9: runs a scheduled job at most once at a time across every backend instance, and reports it.
 *
 * <p>Each run takes a PostgreSQL session advisory lock named after the job, on a connection held
 * for the run. A second instance whose schedule fires meanwhile skips instead of repeating the work
 * (and, for job alerts, the emails). The lock belongs to the connection, so a crashed instance
 * releases it with its connection; nothing needs clearing by hand.
 *
 * <p>A failure is logged with its type and duration and never propagates to the scheduler, so the
 * next run still happens. Every log line of a run carries its {@code jobId}; the outcome and
 * duration are also recorded as the {@code jmip.scheduled.job} timer.
 */
@Component
public class ScheduledJobRunner {

    public static final String MDC_JOB_ID = "jobId";
    static final String LOCK_KEY = "SELECT hashtext(?)::bigint";

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobRunner.class);

    public enum Outcome { COMPLETED, FAILED, SKIPPED }

    private final DataSource dataSource;
    private final ObjectProvider<MeterRegistry> meters;

    public ScheduledJobRunner(DataSource dataSource, ObjectProvider<MeterRegistry> meters) {
        this.dataSource = dataSource;
        this.meters = meters;
    }

    public Outcome run(String job, Runnable task) {
        String jobId = job + "-" + UUID.randomUUID().toString().substring(0, 8);
        MDC.put(MDC_JOB_ID, jobId);
        long started = System.nanoTime();
        Outcome outcome;
        try (Connection lock = dataSource.getConnection()) {
            if (!call(lock, "SELECT pg_try_advisory_lock(hashtext(?))", job)) {
                log.info("scheduled.job name={} status=SKIPPED reason=running-elsewhere", job);
                return record(job, Outcome.SKIPPED, started);
            }
            try {
                task.run();
                outcome = Outcome.COMPLETED;
            } catch (RuntimeException failure) {
                outcome = Outcome.FAILED;
                log.error("scheduled.job name={} status=FAILED durationMs={} error={}", job, elapsedMillis(started),
                        failure.getClass().getSimpleName());
                log.debug("scheduled job failure detail", failure);
            } finally {
                call(lock, "SELECT pg_advisory_unlock(hashtext(?))", job);
            }
        } catch (SQLException unavailable) {
            // Without the database the job could not run anyway; the next schedule tries again.
            log.error("scheduled.job name={} status=FAILED reason=no-database-connection error={}", job,
                    unavailable.getClass().getSimpleName());
            return record(job, Outcome.FAILED, started);
        } finally {
            MDC.remove(MDC_JOB_ID);
        }
        if (outcome == Outcome.COMPLETED) {
            log.info("scheduled.job name={} status=COMPLETED durationMs={}", job, elapsedMillis(started));
        }
        return record(job, outcome, started);
    }

    private static boolean call(Connection connection, String sql, String job) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "jmip:" + job);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private Outcome record(String job, Outcome outcome, long started) {
        MeterRegistry registry = meters.getIfAvailable();
        if (registry != null) {
            Timer.builder("jmip.scheduled.job").tag("job", job).tag("outcome", outcome.name().toLowerCase())
                    .register(registry).record(Duration.ofNanos(System.nanoTime() - started));
        }
        return outcome;
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
