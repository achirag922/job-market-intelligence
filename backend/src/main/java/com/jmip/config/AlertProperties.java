package com.jmip.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * V8.4: job alert digests. Delivery defaults to {@code log}, so development and tests never
 * send real email; {@code smtp} reuses the {@code spring.mail} (JMIP_MAIL_*) settings.
 *
 * @param enabled          whether the scheduled pass runs at all
 * @param cron             when to look for due alerts; each alert is still sent only once per its frequency
 * @param appUrl           the frontend's public address, for the job links in the email
 * @param mailFrom         sender address; blank means the SMTP username
 * @param maxJobsPerDigest the most jobs one email lists
 * @param maxAttempts      how often a failed digest is retried before it is left as FAILED
 */
@ConfigurationProperties(prefix = "jmip.alerts")
public record AlertProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("log") Delivery delivery,
        @DefaultValue("0 0 * * * *") String cron,
        @DefaultValue("http://localhost:5173") String appUrl,
        @DefaultValue("") String mailFrom,
        @DefaultValue("20") int maxJobsPerDigest,
        @DefaultValue("3") int maxAttempts) {

    public enum Delivery {
        SMTP,
        LOG
    }
}
