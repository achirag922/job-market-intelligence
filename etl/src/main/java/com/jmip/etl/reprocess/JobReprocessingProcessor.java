package com.jmip.etl.reprocess;

import com.jmip.etl.model.JobClassification;
import com.jmip.etl.transform.JobClassifier;
import com.jmip.etl.transform.JobDescriptionProcessor;
import com.jmip.etl.transform.SkillExtractor;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Re-runs V4 extraction and classification over a posting already in the database.
 *
 * <p>The same three components the ingestion pipeline uses, in the same order, so a
 * reprocessed posting and a freshly ingested one get identical treatment. Nothing is
 * duplicated here beyond the wiring.
 */
@Component
public class JobReprocessingProcessor implements ItemProcessor<StoredJob, ReprocessedJob> {

    private final JobDescriptionProcessor descriptionProcessor;
    private final SkillExtractor skillExtractor;
    private final JobClassifier jobClassifier;

    public JobReprocessingProcessor(JobDescriptionProcessor descriptionProcessor,
                                    SkillExtractor skillExtractor,
                                    JobClassifier jobClassifier) {
        this.descriptionProcessor = descriptionProcessor;
        this.skillExtractor = skillExtractor;
        this.jobClassifier = jobClassifier;
    }

    @Override
    public ReprocessedJob process(StoredJob job) {
        String processedDescription = descriptionProcessor.process(job.description());
        Set<String> skills = skillExtractor.extract(job.title(), processedDescription);
        JobClassification classification =
                jobClassifier.classify(job.title(), processedDescription, skills);
        return new ReprocessedJob(job.id(), skills, classification);
    }
}
