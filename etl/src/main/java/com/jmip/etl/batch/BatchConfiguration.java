package com.jmip.etl.batch;

import com.jmip.etl.config.EtlProperties;
import com.jmip.etl.load.JobItemWriter;
import com.jmip.etl.load.ReferenceDataCache;
import com.jmip.etl.model.TransformedJob;
import com.jmip.etl.raw.RawJobRecord;
import com.jmip.etl.raw.RawJobRecordReaderFactory;
import com.jmip.etl.transform.JobItemProcessor;
import com.jmip.etl.validation.RecordRejectedException;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Wires the ingestion job.
 *
 * <p>Chunk oriented: each chunk of records is processed and committed as one transaction,
 * so a failure costs at most one chunk rather than the whole run. The job is restartable,
 * which combined with the duplicate constraints means re-running after a failure resumes
 * safely rather than double loading.
 *
 * <p>Retry is deliberately not configured. Nothing in this pipeline is transiently
 * failing yet — a bad record is bad every time — and retry without a genuine transient
 * failure just multiplies work.
 */
@Configuration
public class BatchConfiguration {

    public static final String JOB_NAME = "ingestJobPostings";
    public static final String STEP_NAME = "ingestJobPostingsStep";

    /** Injected rather than called statically, so validation can be tested against a fixed date. */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    /**
     * Resolved per step execution, because the file to read is a job parameter.
     *
     * @param inputFile     path to the dataset, e.g. {@code etl/data/raw/synthetic-job-postings-v1.json}
     * @param defaultSource used for CSV files that carry no source column
     */
    @Bean
    @StepScope
    public ItemStreamReader<RawJobRecord> jobRecordReader(
            @Value("#{jobParameters['inputFile']}") String inputFile,
            @Value("#{jobParameters['defaultSource']}") String defaultSource,
            RawJobRecordReaderFactory readerFactory) {
        if (inputFile == null || inputFile.isBlank()) {
            throw new IllegalArgumentException(
                    "Job parameter 'inputFile' is required, for example: inputFile=etl/data/raw/dataset.json");
        }
        return readerFactory.create(Path.of(inputFile), defaultSource == null ? "unknown" : defaultSource);
    }

    @Bean
    public Step ingestJobPostingsStep(JobRepository jobRepository,
                                      PlatformTransactionManager transactionManager,
                                      ItemStreamReader<RawJobRecord> jobRecordReader,
                                      JobItemProcessor jobItemProcessor,
                                      JobItemWriter jobItemWriter,
                                      RejectedRecordListener rejectedRecordListener,
                                      ReferenceDataCache referenceDataCache,
                                      EtlProperties properties) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<RawJobRecord, TransformedJob>chunk(properties.chunkSize(), transactionManager)
                .reader(jobRecordReader)
                .processor(jobItemProcessor)
                .writer(jobItemWriter)
                .faultTolerant()
                // A rejected record is an expected outcome, not a failure: skip it, keep
                // the evidence, and carry on. Anything else still fails the step.
                .skip(RecordRejectedException.class)
                .skipLimit(properties.skipLimit())
                .listener((SkipListener<RawJobRecord, TransformedJob>) rejectedRecordListener)
                .listener((StepExecutionListener) rejectedRecordListener)
                .listener(referenceDataPrimer(referenceDataCache))
                .build();
    }

    @Bean
    public Job ingestJobPostingsJob(JobRepository jobRepository,
                                    Step ingestJobPostingsStep,
                                    EtlJobListener etlJobListener) {
        return new JobBuilder(JOB_NAME, jobRepository)
                // Each launch is a new job instance; a failed execution can still be
                // restarted by supplying the same parameters.
                .incrementer(new RunIdIncrementer())
                .listener(etlJobListener)
                .start(ingestJobPostingsStep)
                .build();
    }

    /**
     * Reloads the reference caches at the start of every step, so a second run in the
     * same JVM never works from state left by the first.
     */
    private StepExecutionListener referenceDataPrimer(ReferenceDataCache referenceDataCache) {
        return new StepExecutionListener() {
            @Override
            public void beforeStep(StepExecution stepExecution) {
                referenceDataCache.prime();
            }
        };
    }
}
