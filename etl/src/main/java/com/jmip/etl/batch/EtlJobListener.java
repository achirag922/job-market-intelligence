package com.jmip.etl.batch;

import com.jmip.etl.load.EtlMetrics;
import com.jmip.etl.load.EtlRunMetricsRepository;
import com.jmip.etl.load.JobSourceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Logs the start and end of a run, and prints the closing summary.
 */
@Component
public class EtlJobListener implements JobExecutionListener {

    static final String MDC_JOB_EXECUTION_ID = "jobExecutionId";

    private static final Logger log = LoggerFactory.getLogger(EtlJobListener.class);

    private final EtlMetrics metrics;
    private final EtlRunMetricsRepository runMetricsRepository;
    private final JobSourceRegistry sources;
    private final com.jmip.etl.load.JobExpiry jobExpiry;
    private final com.jmip.etl.connector.JobSourceConnectors connectors;

    public EtlJobListener(EtlMetrics metrics, EtlRunMetricsRepository runMetricsRepository, JobSourceRegistry sources,
                          com.jmip.etl.load.JobExpiry jobExpiry, com.jmip.etl.connector.JobSourceConnectors connectors) {
        this.metrics = metrics;
        this.runMetricsRepository = runMetricsRepository;
        this.sources = sources;
        this.jobExpiry = jobExpiry;
        this.connectors = connectors;
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        metrics.reset();
        // V7.8: every log line of this run carries its execution id (structured logs in prod).
        MDC.put(MDC_JOB_EXECUTION_ID, String.valueOf(jobExecution.getId()));
        // V8.1: which feed this run reads; its sources are registered as their records arrive.
        // V9.1: through which connector. The reprocessing job reads no source.
        beginRun(jobExecution);
        log.info("ETL job '{}' started (execution {}), parameters: {}",
                jobExecution.getJobInstance().getJobName(),
                jobExecution.getId(),
                jobExecution.getJobParameters());
    }

    private void beginRun(JobExecution jobExecution) {
        if (BatchConfiguration.REPROCESS_JOB_NAME.equals(jobExecution.getJobInstance().getJobName())) {
            sources.beginRun(jobExecution.getId(), null, null, null);
            return;
        }
        var parameters = jobExecution.getJobParameters();
        String requested = parameters.getString("connector");
        try {
            var connector = connectors.select(requested);
            var feed = connector.describe(new com.jmip.etl.connector.JobSourceConnector.ConnectorRequest(
                    parameters.getString("inputFile"), parameters.getString("defaultSource")));
            sources.beginRun(jobExecution.getId(), connector.name(), feed.name(), feed.type());
        } catch (IllegalArgumentException unknown) {
            // The step fails with the same message when it opens its reader; the run still records what was asked for.
            sources.beginRun(jobExecution.getId(), requested, null, null);
        }
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        expirePastDue(jobExecution);
        long read = 0;
        long written = 0;
        long rejected = 0;
        for (StepExecution step : jobExecution.getStepExecutions()) {
            read += step.getReadCount();
            written += step.getWriteCount();
            rejected += step.getProcessSkipCount() + step.getReadSkipCount() + step.getWriteSkipCount();
        }
        long processed = read - rejected;
        Duration elapsed = elapsed(jobExecution);

        log.info("ETL job '{}' finished with status {}",
                jobExecution.getJobInstance().getJobName(), jobExecution.getStatus());

        if (!jobExecution.getAllFailureExceptions().isEmpty()) {
            jobExecution.getAllFailureExceptions()
                    .forEach(failure -> log.error("Job failure", failure));
        }

        String summary = """

                ============================================
                ETL COMPLETED
                Connector:         %s
                Records Read:      %d
                Records Processed: %d
                Records Loaded:    %d
                Duplicates:        %d
                Rejected:          %d
                Skill Links:       %d
                Expired:           %d
                Execution Time:    %.2f seconds
                Status:            %s
                ============================================"""
                .formatted(sources.connector() == null ? "-" : sources.connector(),
                        read,
                        processed,
                        metrics.jobsLoaded(),
                        metrics.duplicatesSkipped(),
                        rejected,
                        metrics.skillLinksCreated(),
                        metrics.jobsExpired(),
                        elapsed.toMillis() / 1000.0,
                        jobExecution.getStatus());
        log.info(summary);
        // The same figures on one line, for searching and alerting on the log.
        log.info("etl.run executionId={} job={} status={} durationMs={} read={} processed={} loaded={} duplicates={} rejected={} expired={} connector={}",
                jobExecution.getId(), jobExecution.getJobInstance().getJobName(), jobExecution.getStatus(),
                elapsed.toMillis(), read, processed, metrics.jobsLoaded(), metrics.duplicatesSkipped(), rejected,
                metrics.jobsExpired(), sources.connector());
        persistRunMetrics(jobExecution);
        MDC.remove(MDC_JOB_EXECUTION_ID);
    }

    /**
     * Stores the counters Spring Batch does not keep, for the V6.5 monitoring API. A failure
     * here must not change the outcome of a run whose data is already committed, so it is
     * logged rather than thrown.
     */
    /**
     * V8.2: jobs whose source-given expiry date has passed are marked inactive, never deleted.
     * Like the metrics below, a failure here is logged rather than failing a committed run.
     */
    private void expirePastDue(JobExecution jobExecution) {
        try {
            metrics.recordExpired(jobExpiry.expirePastDue());
        } catch (RuntimeException exception) {
            log.warn("Could not expire past-due jobs after execution {}", jobExecution.getId(), exception);
        }
    }

    private void persistRunMetrics(JobExecution jobExecution) {
        try {
            runMetricsRepository.save(jobExecution.getId(), metrics, sources.feedName(), sources.feedType(),
                    sources.connector());
            sources.finishRun();
        } catch (RuntimeException exception) {
            log.warn("Could not record run metrics for execution {}", jobExecution.getId(), exception);
        }
    }

    private Duration elapsed(JobExecution jobExecution) {
        LocalDateTime start = jobExecution.getStartTime();
        LocalDateTime end = jobExecution.getEndTime();
        if (start == null) {
            return Duration.ZERO;
        }
        return Duration.between(start, end == null ? LocalDateTime.now() : end);
    }
}
