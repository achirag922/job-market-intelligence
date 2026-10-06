package com.jmip.service.workspace;

import com.jmip.config.AlertProperties;
import com.jmip.config.ScheduledJobRunner;
import com.jmip.repository.WorkspaceRepository;
import com.jmip.repository.WorkspaceRepository.DueFollowUp;
import com.jmip.service.alert.AlertDigest;
import com.jmip.service.alert.AlertEmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * V9.14: one email per user listing the follow-ups that fell due, through the existing job-alert
 * delivery (log or SMTP, {@code JMIP_ALERTS_DELIVERY}) and the V9.9 scheduled-job runner. Each
 * follow-up date is reminded once; setting a new date makes it eligible again. Off when job alerts
 * are disabled.
 */
@Component
public class FollowUpReminderJob {

    private static final Logger log = LoggerFactory.getLogger(FollowUpReminderJob.class);

    private final WorkspaceRepository repository;
    private final AlertEmailSender sender;
    private final AlertProperties properties;
    private final ScheduledJobRunner runner;
    private final Clock clock;

    public FollowUpReminderJob(WorkspaceRepository repository, AlertEmailSender sender, AlertProperties properties,
                               ScheduledJobRunner runner, Clock clock) {
        this.repository = repository;
        this.sender = sender;
        this.properties = properties;
        this.runner = runner;
        this.clock = clock;
    }

    @Scheduled(cron = "${jmip.followups.cron:0 0 8 * * *}")
    public void onSchedule() {
        if (properties.enabled()) {
            runner.run("follow-up-reminders", this::sendDue);
        }
    }

    /** @return how many users were reminded */
    public int sendDue() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        Map<UUID, List<DueFollowUp>> byUser = repository.dueFollowUps(today).stream()
                .collect(Collectors.groupingBy(DueFollowUp::userId, LinkedHashMap::new, Collectors.toList()));
        int reminded = 0;
        for (List<DueFollowUp> due : byUser.values()) {
            try {
                sender.send(due.get(0).email(), digest(due, today));
                repository.markReminded(due.stream().map(DueFollowUp::savedJobId).toList());
                reminded++;
            } catch (RuntimeException failure) {
                // One failed delivery must not stop the others; it is tried again on the next run.
                log.warn("Follow-up reminder could not be sent: {}", failure.getClass().getSimpleName());
            }
        }
        log.info("followups.run users={} followUps={}", reminded, byUser.values().stream().mapToInt(List::size).sum());
        return reminded;
    }

    AlertDigest digest(List<DueFollowUp> due, LocalDate today) {
        String name = due.get(0).fullName() == null ? "there" : due.get(0).fullName().split(" ")[0];
        StringBuilder body = new StringBuilder("Hi ").append(name).append(",\n\nThese follow-ups are due:\n\n");
        for (DueFollowUp item : due) {
            body.append("- ").append(item.jobTitle()).append(" at ").append(item.company())
                    .append(item.followUpOn().isBefore(today) ? " (was due " + item.followUpOn() + ")" : " (due today)");
            if (item.note() != null && !item.note().isBlank()) {
                body.append(": ").append(item.note());
            }
            body.append('\n');
        }
        body.append("\nSee them in your workspace: ").append(properties.appUrl()).append("/workspace\n");
        String subject = "JMIP: " + due.size() + " follow-up" + (due.size() == 1 ? "" : "s") + " due";
        return new AlertDigest(subject, body.toString());
    }
}
