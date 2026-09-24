package com.jmip.service.resume;

import com.jmip.config.ResumePrivacyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * Deletes resumes older than the configured retention period. Off by default: with no period
 * set, a resume is kept until its owner deletes it. Logs counts only, never ids or content.
 */
@Component
public class ResumeRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(ResumeRetentionJob.class);
    /** Per transaction, so a large backlog never holds one long lock. */
    static final int BATCH_SIZE = 200;

    private final ResumeService resumeService;
    private final ResumePrivacyProperties properties;
    private final Clock clock;

    public ResumeRetentionJob(ResumeService resumeService, ResumePrivacyProperties properties, Clock clock) {
        this.resumeService = resumeService;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${jmip.resume.privacy.retention-cron:0 30 3 * * *}")
    public void scheduledSweep() {
        sweep();
    }

    /** @return how many resumes were deleted; zero when retention is off */
    public int sweep() {
        if (!properties.retentionEnabled()) {
            return 0;
        }
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(properties.retention());
        int total = 0;
        int deleted;
        do {
            deleted = resumeService.deleteUploadedBefore(cutoff, BATCH_SIZE);
            total += deleted;
        } while (deleted == BATCH_SIZE);
        if (total > 0) {
            log.info("Retention: deleted {} resume(s) older than {}", total, properties.retention());
        }
        return total;
    }
}
