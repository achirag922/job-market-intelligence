package com.jmip.etl.batch;

import com.jmip.etl.raw.RawJobRecord;
import com.jmip.etl.model.TransformedJob;
import com.jmip.etl.validation.RecordRejectedException;
import com.jmip.etl.validation.RejectedRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.stereotype.Component;

/**
 * Records every skipped item so that a rejection is always explained and always kept.
 */
@Component
public class RejectedRecordListener implements SkipListener<RawJobRecord, TransformedJob>, StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(RejectedRecordListener.class);

    private final RejectedRecordRepository repository;

    private String jobName;
    private long jobExecutionId;

    public RejectedRecordListener(RejectedRecordRepository repository) {
        this.repository = repository;
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {
        this.jobName = stepExecution.getJobExecution().getJobInstance().getJobName();
        this.jobExecutionId = stepExecution.getJobExecution().getId();
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        return null;
    }

    @Override
    public void onSkipInProcess(RawJobRecord item, Throwable throwable) {
        String reason = throwable instanceof RecordRejectedException rejected
                ? String.join("; ", rejected.reasons())
                : throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        log.info("Rejected '{}' from {}: {}", item.title(), item.source(), reason);
        repository.save(jobName, jobExecutionId, reason, item);
    }

    @Override
    public void onSkipInRead(Throwable throwable) {
        // A read failure has no item to keep: the reader could not produce one.
        log.error("Failed to read a record, it has been skipped", throwable);
    }

    @Override
    public void onSkipInWrite(TransformedJob item, Throwable throwable) {
        log.error("Failed to write '{}', it has been skipped", item.title(), throwable);
    }
}
