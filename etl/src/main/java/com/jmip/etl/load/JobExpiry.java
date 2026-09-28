package com.jmip.etl.load;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;

/**
 * V8.2: marks jobs inactive once the date their source gave for closing has passed.
 *
 * <p>Only a source-given expiry date counts as reliable. A posting missing from one feed is
 * not treated as closed, because file feeds are often partial. Expired jobs are never deleted:
 * history and analytics keep them.
 */
@Component
public class JobExpiry {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public JobExpiry(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /** @return how many open jobs were marked expired */
    public int expirePastDue() {
        return jdbcTemplate.update(
                "UPDATE jobs SET active = FALSE, deactivated_at = now() WHERE active AND expires_at < ?",
                Date.valueOf(LocalDate.now(clock)));
    }
}
