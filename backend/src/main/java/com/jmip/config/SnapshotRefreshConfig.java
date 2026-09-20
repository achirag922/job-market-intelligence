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

    public SnapshotRefreshConfig(SkillTrendRepository skillTrendRepository, SnapshotProperties properties) {
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

    private void refresh(String trigger) {
        try {
            skillTrendRepository.rebuild();
        } catch (RuntimeException e) {
            // Trends are a secondary view. A failure here must not stop the API serving
            // everything else, so it is logged rather than propagated.
            log.error("Skill demand history refresh triggered by {} failed", trigger, e);
        }
    }
}
