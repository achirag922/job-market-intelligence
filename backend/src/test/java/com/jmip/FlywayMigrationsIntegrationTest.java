package com.jmip;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V10.1: the migrations a production database will run. A fresh PostgreSQL is migrated the way
 * production does it (clean disabled), then validated, and a second run must have nothing to do.
 */
@Testcontainers
class FlywayMigrationsIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static Flyway flyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load();
    }

    @Test
    @DisplayName("versions are whole numbers from 1 with no gaps or duplicates")
    void versionsAreSequential() {
        int[] versions = Arrays.stream(flyway().info().all())
                .mapToInt(info -> Integer.parseInt(info.getVersion().getVersion()))
                .sorted().toArray();
        assertThat(versions).isNotEmpty().containsExactly(IntStream.rangeClosed(1, versions.length).toArray());
    }

    @Test
    @DisplayName("a fresh database migrates, validates, and a second run is a no-op")
    void migratesFreshDatabase() {
        Flyway flyway = flyway();
        assertThat(flyway.migrate().success).isTrue();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(Arrays.stream(flyway.info().applied()).map(MigrationInfo::getState).distinct())
                .allMatch(state -> state.isApplied() && !state.isFailed());
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
}
