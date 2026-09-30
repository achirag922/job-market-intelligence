package com.jmip.integration;

import com.jmip.config.ScheduledJobRunner;
import com.jmip.config.ScheduledJobRunner.Outcome;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V9.9: scheduled jobs run once across instances. Another instance is simulated by a second
 * connection holding the job's advisory lock.
 */
@SpringBootTest(properties = {"jmip.ai.provider=stub", "jmip.alerts.enabled=false",
        "jmip.analytics.snapshots.refresh-on-startup=false"})
@Testcontainers
class ScheduledJobRunnerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    private ScheduledJobRunner runner;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MeterRegistry meters;

    @Test
    @DisplayName("a job already running on another instance is skipped, then runs once the lock is free")
    void skipsWhileHeldElsewhere() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        try (Connection other = dataSource.getConnection(); Statement statement = other.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(hashtext('jmip:job-alerts'))");
            assertThat(runner.run("job-alerts", runs::incrementAndGet)).isEqualTo(Outcome.SKIPPED);
            assertThat(runs).hasValue(0);
            // A different job is not blocked by it.
            assertThat(runner.run("resume-retention", runs::incrementAndGet)).isEqualTo(Outcome.COMPLETED);
            statement.execute("SELECT pg_advisory_unlock(hashtext('jmip:job-alerts'))");
        }
        assertThat(runner.run("job-alerts", runs::incrementAndGet)).isEqualTo(Outcome.COMPLETED);
        assertThat(runs).hasValue(2);
        assertThat(meters.find("jmip.scheduled.job").tag("job", "job-alerts").tag("outcome", "skipped").timer())
                .isNotNull();
    }

    @Test
    @DisplayName("a failing job is reported, never thrown at the scheduler, and releases its lock")
    void failureIsContained() {
        assertThat(runner.run("skill-snapshots", () -> {
            throw new IllegalStateException("boom");
        })).isEqualTo(Outcome.FAILED);
        assertThat(runner.run("skill-snapshots", () -> { })).isEqualTo(Outcome.COMPLETED);
        assertThat(meters.find("jmip.scheduled.job").tag("job", "skill-snapshots").tag("outcome", "failed").timer()
                .count()).isEqualTo(1);
    }
}
