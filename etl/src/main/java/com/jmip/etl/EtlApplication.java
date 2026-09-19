package com.jmip.etl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the ingestion pipeline.
 *
 * <p>Deliberately a separate application from the REST API: ingestion is a batch workload
 * with a different lifecycle, and running it must never affect API availability. The two
 * share only the database schema, via the {@code database} module.
 *
 * <p>Run with the input file as a job parameter:
 * <pre>java -jar etl.jar inputFile=etl/data/raw/synthetic-job-postings-v1.json</pre>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class EtlApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(EtlApplication.class, args)));
    }
}
