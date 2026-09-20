package com.jmip.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Supplies the clock, rather than letting services call {@code now()} directly, so that
 * anything time-dependent can be tested against a fixed instant. The ETL module does the
 * same.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
