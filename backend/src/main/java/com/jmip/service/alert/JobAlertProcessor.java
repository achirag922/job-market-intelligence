package com.jmip.service.alert;

import com.jmip.config.AlertProperties;
import com.jmip.dto.resume.MatchPreferences;
import com.jmip.entity.Job;
import com.jmip.entity.JobAlert;
import com.jmip.entity.Skill;
import com.jmip.repository.AlertNotificationRepository;
import com.jmip.repository.AlertNotificationRepository.DueAlert;
import com.jmip.repository.AlertNotificationRepository.Notification;
import com.jmip.repository.JobAlertRepository;
import com.jmip.repository.JobRepository;
import com.jmip.repository.MatchPreferencesRepository;
import com.jmip.service.JobService;
import com.jmip.service.resume.JobMatchScorer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * V8.4: the scheduled job-alert pass. Runs on its own schedule in the API, never inside the
 * ETL, so ingestion never waits on email.
 *
 * <p>For each due alert, one transaction records every newly seen active posting that matches
 * the alert's criteria (the Job Explorer's own filters), with its V8.3 match score, and closes
 * the window. The digest is then sent outside that transaction, and its jobs marked SENT or
 * FAILED. A job is recorded once per alert, so it is never sent twice; a failed digest is
 * retried on later passes up to {@code maxAttempts}.
 */
@Service
public class JobAlertProcessor {

    private static final String FIRSTSEENAT = "firstSeenAt";

    private static final Logger log = LoggerFactory.getLogger(JobAlertProcessor.class);

    /** The most new postings one pass records for an alert; the rest wait for the next window. */
    static final int MAX_CANDIDATES = 100;

    private final AlertNotificationRepository notifications;
    private final JobAlertRepository alerts;
    private final JobRepository jobs;
    private final JobService jobService;
    private final JobMatchScorer scorer;
    private final MatchPreferencesRepository preferences;
    private final AlertEmailSender sender;
    private final AlertProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final com.jmip.service.resume.MatchGoals goals;
    private final com.jmip.config.ScheduledJobRunner runner;

    public JobAlertProcessor(AlertNotificationRepository notifications, JobAlertRepository alerts, JobRepository jobs,
                             JobService jobService, JobMatchScorer scorer, MatchPreferencesRepository preferences,
                             AlertEmailSender sender, AlertProperties properties, TransactionTemplate transaction,
                             Clock clock, com.jmip.service.resume.MatchGoals goals, com.jmip.config.ScheduledJobRunner runner) {
        this.goals = goals;
        this.runner = runner;
        this.notifications = notifications;
        this.alerts = alerts;
        this.jobs = jobs;
        this.jobService = jobService;
        this.scorer = scorer;
        this.preferences = preferences;
        this.sender = sender;
        this.properties = properties;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** What one pass did. */
    public record Result(int dueAlerts, int jobsRecorded, int digestsSent, int digestsFailed) {
    }

    @Scheduled(cron = "${jmip.alerts.cron:0 0 * * * *}")
    public void onSchedule() {
        if (properties.enabled()) {
            // V9.9: once across instances, with its duration and failures reported.
            runner.run("job-alerts", this::processDue);
        }
    }

    public Result processDue() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<DueAlert> due = notifications.findDue(now);
        int recorded = 0;
        int sent = 0;
        int failed = 0;
        for (DueAlert alert : due) {
            try {
                Integer count = transaction.execute(status -> recordMatches(alert, now));
                recorded += count == null ? 0 : count;
                Boolean delivered = deliver(alert);
                if (Boolean.TRUE.equals(delivered)) {
                    sent++;
                } else if (Boolean.FALSE.equals(delivered)) {
                    failed++;
                }
            } catch (RuntimeException exception) {
                // One broken alert must not stop the others; the type is enough to investigate.
                failed++;
                log.warn("Job alert {} could not be processed: {}", alert.alertId(), exception.getClass().getSimpleName());
            }
        }
        log.info("alerts.run due={} jobsRecorded={} digestsSent={} digestsFailed={}", due.size(), recorded, sent, failed);
        return new Result(due.size(), recorded, sent, failed);
    }

    /** Records the alert's new matches as pending and closes its window. */
    private int recordMatches(DueAlert due, OffsetDateTime now) {
        JobAlert alert = alerts.findById(due.alertId()).orElse(null);
        if (alert == null || !alert.isActive()) {
            return 0;
        }
        Specification<Job> newlySeen = (root, query, cb) -> cb.and(
                cb.greaterThan(root.get(FIRSTSEENAT), due.since()),
                cb.lessThanOrEqualTo(root.get(FIRSTSEENAT), now),
                cb.isTrue(root.get("active")));
        List<Job> matches = jobs.findAll(jobService.toSpecification(alert.toSearchCriteria()).and(newlySeen),
                PageRequest.of(0, MAX_CANDIDATES, Sort.by(Sort.Order.desc(FIRSTSEENAT), Sort.Order.asc("id")))).getContent();

        Set<Long> already = notifications.recordedJobIds(due.alertId(), matches.stream().map(Job::getId).toList());
        Set<Long> resumeSkills = notifications.currentResumeSkillIds(due.userId());
        MatchPreferences prefs = resumeSkills.isEmpty() ? MatchPreferences.NONE : preferences.find(due.userId());
        JobMatchScorer.GoalContext goal = resumeSkills.isEmpty() ? JobMatchScorer.GoalContext.NONE : goals.forUser(due.userId());
        int recorded = 0;
        for (Job job : matches) {
            if (already.contains(job.getId())) {
                continue;
            }
            notifications.recordPending(due.alertId(), due.userId(), job.getId(), matchScore(job, resumeSkills, prefs, goal));
            recorded++;
        }
        notifications.markProcessed(due.alertId(), now);
        return recorded;
    }

    /** The V8.3 overall match against the user's current resume, or null without one. */
    private Double matchScore(Job job, Set<Long> resumeSkills, MatchPreferences prefs, JobMatchScorer.GoalContext goal) {
        if (resumeSkills.isEmpty()) {
            return null;
        }
        // V9.3: the same engine as matches and recommendations.
        return scorer.score(resumeSkills, job, prefs, goal).overallPercentage();
    }

    /** @return true when a digest went out, false when it failed, null when there was nothing to send */
    private Boolean deliver(DueAlert due) {
        List<Notification> unsent = notifications.unsent(due.alertId(), properties.maxAttempts(), properties.maxJobsPerDigest());
        if (unsent.isEmpty()) {
            return null;
        }
        List<Long> ids = unsent.stream().map(Notification::id).toList();
        Map<Long, Job> byId = jobs.findRecommendationDetailsByIdIn(unsent.stream().map(Notification::jobId).toList())
                .stream().collect(Collectors.toMap(Job::getId, Function.identity()));
        List<AlertDigest.Item> items = unsent.stream()
                .filter(notification -> byId.containsKey(notification.jobId()))
                .map(notification -> item(byId.get(notification.jobId()), notification))
                .toList();
        try {
            sender.send(due.email(), AlertDigest.compose(due.fullName(), due.name(), due.frequency(), items, properties.appUrl()));
            notifications.markSent(ids);
            return true;
        } catch (RuntimeException exception) {
            notifications.markFailed(ids, exception.getClass().getSimpleName());
            log.warn("Digest for job alert {} was not delivered ({}); it is retried on the next pass",
                    due.alertId(), exception.getClass().getSimpleName());
            return false;
        }
    }

    private static AlertDigest.Item item(Job job, Notification notification) {
        String location = job.getLocation() == null ? null
                : String.join(", ", java.util.stream.Stream.of(job.getLocation().getCity(), job.getLocation().getState(),
                        job.getLocation().getCountry()).filter(part -> part != null && !part.isBlank()).toList());
        String mode = JobMatchScorer.workModeOf(job.getDescription());
        String workMode = mode == null ? null : switch (mode) {
            case "REMOTE" -> "Remote";
            case "HYBRID" -> "Hybrid";
            default -> "On-site";
        };
        return new AlertDigest.Item(job.getId(), job.getTitle(), job.getCompany().getName(), location, workMode,
                notification.matchPercentage() == null ? null : notification.matchPercentage().doubleValue());
    }
}
