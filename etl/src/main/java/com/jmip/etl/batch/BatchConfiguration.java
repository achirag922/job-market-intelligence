package com.jmip.etl.batch;

import com.jmip.etl.config.EtlProperties;
import com.jmip.etl.load.JobItemWriter;
import com.jmip.etl.reprocess.JobReprocessingProcessor;
import com.jmip.etl.reprocess.JobReprocessingWriter;
import com.jmip.etl.reprocess.ReprocessedJob;
import com.jmip.etl.reprocess.StoredJob;
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
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.support.PostgresPagingQueryProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Map;
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
    public static final String REPROCESS_JOB_NAME = "reprocessJobPostings";
    public static final String REPROCESS_STEP_NAME = "reprocessJobPostingsStep";

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

    /**
     * Reads existing postings for reprocessing, a page at a time.
     *
     * <p>Paged rather than cursor based, and ordered by id, because this step updates the
     * very rows it is reading. Only three columns are selected: descriptions are the
     * largest column in the table and the whole corpus must never be held in memory.
     */
    @Bean
    public JdbcPagingItemReader<StoredJob> storedJobReader(DataSource dataSource, EtlProperties properties) {
        PostgresPagingQueryProvider queryProvider = new PostgresPagingQueryProvider();
        queryProvider.setSelectClause("SELECT id, title, description");
        queryProvider.setFromClause("FROM jobs");
        queryProvider.setSortKeys(Map.of("id", Order.ASCENDING));

        return new JdbcPagingItemReaderBuilder<StoredJob>()
                .name("storedJobReader")
                .dataSource(dataSource)
                .queryProvider(queryProvider)
                .pageSize(properties.chunkSize())
                .rowMapper((rs, rowNum) -> new StoredJob(
                        rs.getLong("id"), rs.getString("title"), rs.getString("description")))
                .build();
    }

    @Bean
    public Step reprocessJobPostingsStep(JobRepository jobRepository,
                                         PlatformTransactionManager transactionManager,
                                         JdbcPagingItemReader<StoredJob> storedJobReader,
                                         JobReprocessingProcessor jobReprocessingProcessor,
                                         JobReprocessingWriter jobReprocessingWriter,
                                         ReferenceDataCache referenceDataCache,
                                         EtlProperties properties) {
        return new StepBuilder(REPROCESS_STEP_NAME, jobRepository)
                .<StoredJob, ReprocessedJob>chunk(properties.chunkSize(), transactionManager)
                .reader(storedJobReader)
                .processor(jobReprocessingProcessor)
                .writer(jobReprocessingWriter)
                .listener(referenceDataPrimer(referenceDataCache))
                .build();
    }

    /**
     * Re-runs V4 extraction and classification over postings already in the database.
     *
     * <p>Separate from ingestion because it answers a different question: ingestion adds
     * postings, this one brings existing postings up to date with the current dictionary
     * and rules. Launch it with
     * {@code --spring.batch.job.name=reprocessJobPostings}; without that the ingestion
     * job runs as before, so nothing about the existing command changes.
     */
    @Bean
    public Job reprocessJobPostingsJob(JobRepository jobRepository,
                                       Step reprocessJobPostingsStep,
                                       EtlJobListener etlJobListener) {
        return new JobBuilder(REPROCESS_JOB_NAME, jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(etlJobListener)
                .start(reprocessJobPostingsStep)
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
