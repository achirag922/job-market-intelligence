package com.jmip.etl.batch;

import com.jmip.etl.load.EtlMetrics;
import com.jmip.etl.load.EtlRunMetricsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(EtlJobListener.class);

    private final EtlMetrics metrics;
    private final EtlRunMetricsRepository runMetricsRepository;

    public EtlJobListener(EtlMetrics metrics, EtlRunMetricsRepository runMetricsRepository) {
        this.metrics = metrics;
        this.runMetricsRepository = runMetricsRepository;
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        metrics.reset();
        log.info("ETL job '{}' started (execution {}), parameters: {}",
                jobExecution.getJobInstance().getJobName(),
                jobExecution.getId(),
                jobExecution.getJobParameters());
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
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
                Records Read:      %d
                Records Processed: %d
                Records Loaded:    %d
                Duplicates:        %d
                Rejected:          %d
                Skill Links:       %d
                Execution Time:    %.2f seconds
                Status:            %s
                ============================================"""
                .formatted(read,
                        processed,
                        metrics.jobsLoaded(),
                        metrics.duplicatesSkipped(),
                        rejected,
                        metrics.skillLinksCreated(),
                        elapsed.toMillis() / 1000.0,
                        jobExecution.getStatus());
        log.info(summary);
        persistRunMetrics(jobExecution);
    }

    /**
     * Stores the counters Spring Batch does not keep, for the V6.5 monitoring API. A failure
     * here must not change the outcome of a run whose data is already committed, so it is
     * logged rather than thrown.
     */
    private void persistRunMetrics(JobExecution jobExecution) {
        try {
            runMetricsRepository.save(jobExecution.getId(), metrics);
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
