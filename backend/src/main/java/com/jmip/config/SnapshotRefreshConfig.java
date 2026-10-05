package com.jmip.config;

import com.jmip.repository.SkillTrendRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Keeps the skill demand history in step with the postings.
 *
 * <p>The snapshots are derived data: everything in them can be recomputed from
 * {@code jobs.posted_date}, so refreshing is always safe and never loses anything.
 *
 * <p>Two triggers, for two different situations. A rebuild at startup means the history is
 * correct as soon as the API is up, which matters because the ETL runs as a separate
 * application and the API has no way of knowing it finished. A daily rebuild keeps a
 * long-running instance current without a restart.
 *
 * <p>Both can be turned off, and the schedule changed, through configuration. If the
 * posting count ever grows enough that a full rebuild at startup is noticeable, the
 * startup trigger is the one to disable first.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(SnapshotProperties.class)
public class SnapshotRefreshConfig {

    private static final Logger log = LoggerFactory.getLogger(SnapshotRefreshConfig.class);

    private final SkillTrendRepository skillTrendRepository;
    private final SnapshotProperties properties;
    private final ScheduledJobRunner runner;

    public SnapshotRefreshConfig(SkillTrendRepository skillTrendRepository, SnapshotProperties properties,
                                 ScheduledJobRunner runner) {
        this.runner = runner;
        this.skillTrendRepository = skillTrendRepository;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartup() {
        if (!properties.refreshOnStartup()) {
            log.debug("Skill demand history refresh on startup is disabled");
            return;
        }
        refresh("startup");
    }

    @Scheduled(cron = "${jmip.analytics.snapshots.cron:0 0 2 * * *}")
    public void refreshOnSchedule() {
        refresh("schedule");
    }

    /**
     * Trends are a secondary view: a failure is logged by the runner rather than propagated, so it
     * never stops the API serving everything else. V9.9: one rebuild at a time across instances.
     */
    private void refresh(String trigger) {
        log.debug("Skill demand history refresh triggered by {}", trigger);
        runner.run("skill-snapshots", skillTrendRepository::rebuild);
    }
}
