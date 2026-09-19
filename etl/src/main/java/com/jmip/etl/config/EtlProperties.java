package com.jmip.etl.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning knobs for the ingestion job.
 *
 * @param chunkSize how many records are processed and committed as one transaction
 * @param skipLimit how many rejected records are tolerated before the job fails outright;
 *                  a run where almost everything is rejected usually means the wrong file
 *                  or the wrong column mapping, and should stop rather than grind on
 */
@ConfigurationProperties(prefix = "jmip.etl")
public record EtlProperties(int chunkSize, int skipLimit) {

    public EtlProperties {
        if (chunkSize <= 0) {
            chunkSize = 100;
        }
        if (skipLimit < 0) {
            skipLimit = 500;
        }
    }
}
