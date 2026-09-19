package com.jmip.etl.load;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters Spring Batch does not keep for us.
 *
 * <p>Read, processed and skipped counts come from the {@code StepExecution}. How many
 * records turned out to be duplicates, and how many rows were actually written, are
 * ingestion concerns the framework knows nothing about.
 */
@Component
public class EtlMetrics {

    private final AtomicLong jobsLoaded = new AtomicLong();
    private final AtomicLong duplicatesSkipped = new AtomicLong();
    private final AtomicLong skillLinksCreated = new AtomicLong();

    public void reset() {
        jobsLoaded.set(0);
        duplicatesSkipped.set(0);
        skillLinksCreated.set(0);
    }

    public void recordJobsLoaded(long count) {
        jobsLoaded.addAndGet(count);
    }

    public void recordDuplicates(long count) {
        duplicatesSkipped.addAndGet(count);
    }

    public void recordSkillLinks(long count) {
        skillLinksCreated.addAndGet(count);
    }

    public long jobsLoaded() {
        return jobsLoaded.get();
    }

    public long duplicatesSkipped() {
        return duplicatesSkipped.get();
    }

    public long skillLinksCreated() {
        return skillLinksCreated.get();
    }
}
