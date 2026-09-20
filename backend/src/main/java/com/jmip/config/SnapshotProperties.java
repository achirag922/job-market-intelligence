package com.jmip.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * When the derived skill demand history is refreshed.
 *
 * <p>Both values carry an explicit {@link DefaultValue}. Without one, an unset boolean
 * binds to {@code false} and the refresh quietly never runs — a failure with no error and
 * no symptom except permanently empty trends.
 *
 * @param refreshOnStartup rebuild once the application is ready, so a fresh ETL load is
 *                         reflected without waiting for the next scheduled run
 * @param cron             schedule for the recurring rebuild
 */
@ConfigurationProperties(prefix = "jmip.analytics.snapshots")
public record SnapshotProperties(
        @DefaultValue("true") boolean refreshOnStartup,
        @DefaultValue("0 0 2 * * *") String cron) {

    public SnapshotProperties {
        cron = cron == null || cron.isBlank() ? "0 0 2 * * *" : cron;
    }
}
