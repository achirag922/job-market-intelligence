package com.jmip.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * V7.8 observability settings.
 *
 * @param metricsUsername      the one account allowed to read /actuator/metrics (HTTP Basic)
 * @param metricsPassword      its password; unset means the metrics endpoints are closed to everyone
 * @param slowRequestThreshold requests at least this slow are logged at INFO; faster ones at DEBUG
 */
@ConfigurationProperties(prefix = "jmip.observability")
public record ObservabilityProperties(
        @DefaultValue("metrics") String metricsUsername,
        @DefaultValue("") String metricsPassword,
        @DefaultValue("1s") Duration slowRequestThreshold) {

    /** Long enough that guessing it over HTTP Basic is not a realistic attack. */
    static final int MIN_PASSWORD_LENGTH = 16;

    public ObservabilityProperties {
        if (metricsPassword != null && !metricsPassword.isBlank() && metricsPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "JMIP_METRICS_PASSWORD must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }

    public boolean metricsEnabled() {
        return metricsPassword != null && !metricsPassword.isBlank();
    }

    /** Never prints the password. */
    @Override
    public String toString() {
        return "ObservabilityProperties[metricsUsername=" + metricsUsername + ", metricsPassword="
                + (metricsEnabled() ? "****" : "<unset>") + ", slowRequestThreshold=" + slowRequestThreshold + "]";
    }
}
