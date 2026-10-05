package com.jmip.service.notification;

import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.dto.notification.NotificationDtos.Inbox;
import com.jmip.dto.notification.NotificationDtos.Preferences;
import com.jmip.dto.notification.NotificationDtos.UnreadCount;
import com.jmip.dto.progress.CareerProgressDtos.Achievement;
import com.jmip.repository.NotificationRepository;
import com.jmip.repository.NotificationRepository.Draft;
import com.jmip.service.auth.CurrentUser;
import com.jmip.service.progress.CareerProgressService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * V9.16: the signed-in user's notification center. Notifications are derived on read from what JMIP
 * already records, never from new alert logic: job-alert matches the V8.4 processor recorded, V8.5
 * follow-up dates, applications at the interview stage, V9.5 learning target dates and V9.13
 * achievements. Each has a stable key, so it appears once and keeps its read state. Derivation runs
 * at most every few minutes per user, so opening the inbox or polling the badge stays cheap.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    static final Duration REFRESH_EVERY = Duration.ofMinutes(5);
    static final int LIMIT = 100;
    private static final int RECENT_DAYS = 14;

    private final NotificationRepository repository;
    private final CareerProgressService progress;
    private final CurrentUser currentUser;
    private final Clock clock;

    public NotificationService(NotificationRepository repository, CareerProgressService progress, CurrentUser currentUser,
                               Clock clock) {
        this.repository = repository;
        this.progress = progress;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Transactional
    public Inbox inbox(boolean unreadOnly) {
        UUID owner = currentUser.requireId();
        Preferences prefs = refreshIfStale(owner, false);
        List<String> types = enabled(prefs);
        return new Inbox(repository.list(owner, types, unreadOnly, LIMIT), repository.unread(owner, types));
    }

    @Transactional
    public UnreadCount unreadCount() {
        UUID owner = currentUser.requireId();
        return new UnreadCount(repository.unread(owner, enabled(refreshIfStale(owner, false))));
    }

    @Transactional
    public void markRead(UUID id) {
        if (!repository.markRead(id, currentUser.requireId(), now())) {
            throw ResourceNotFoundException.of("Notification", id);
        }
    }

    @Transactional
    public UnreadCount markAllRead() {
        UUID owner = currentUser.requireId();
        repository.markAllRead(owner, now());
        return new UnreadCount(0);
    }

    @Transactional(readOnly = true)
    public Preferences preferences() {
        return repository.preferences(currentUser.requireId());
    }

    /** Saves the choice and derives again at once, so a kind switched back on shows up immediately. */
    @Transactional
    public Preferences savePreferences(Preferences preferences) {
        UUID owner = currentUser.requireId();
        repository.savePreferences(owner, preferences);
        refreshIfStale(owner, true);
        return repository.preferences(owner);
    }

    // ------------------------------------------------------------------ derivation

    private Preferences refreshIfStale(UUID owner, boolean force) {
        Preferences prefs = repository.preferences(owner);
        OffsetDateTime now = now();
        boolean stale = force || repository.refreshedAt(owner).map(at -> at.plus(REFRESH_EVERY).isBefore(now)).orElse(true);
        if (stale) {
            repository.insertNew(owner, drafts(owner, prefs, LocalDate.now(clock.withZone(ZoneOffset.UTC))), now);
            repository.touchRefreshed(owner, now);
        }
        return prefs;
    }

    List<Draft> drafts(UUID owner, Preferences prefs, LocalDate today) {
        List<Draft> drafts = new ArrayList<>();
        if (prefs.jobMatches()) {
            repository.recentAlertMatches(owner, today.minusDays(RECENT_DAYS)).forEach(group -> drafts.add(new Draft("JOB_MATCH",
                    "match:" + group.alertId() + ":" + group.day(),
                    group.jobs() + " new job" + (group.jobs() == 1 ? "" : "s") + " match “" + group.alertName() + "”",
                    "Found on " + group.day() + " by your job alert.", "/alerts")));
        }
        if (prefs.followUps()) {
            repository.followUps(owner, today.plusDays(1)).forEach(f -> drafts.add(new Draft("FOLLOW_UP",
                    "follow-up:" + f.id() + ":" + f.on(),
                    (f.on().isBefore(today) ? "Follow-up overdue: " : f.on().equals(today) ? "Follow up today: " : "Follow up tomorrow: ")
                            + f.title(), f.company() + " · due " + f.on(), "/workspace")));
        }
        if (prefs.interviews()) {
            repository.interviewStage(owner).forEach(i -> drafts.add(new Draft("INTERVIEW", "interview:" + i.id(),
                    "Interview stage: " + i.title(), "Practise for " + i.company() + " with a mock interview.", "/interview-prep")));
        }
        if (prefs.learning()) {
            repository.learningDue(owner, today.plusDays(3)).forEach(l -> drafts.add(new Draft("LEARNING",
                    "learning:" + l.id() + ":" + l.on(),
                    (l.on().isBefore(today) ? "Learning target passed: " : "Learning target soon: ") + l.company(),
                    l.title() + " · target " + l.on(), "/learning")));
        }
        if (prefs.career()) {
            try {
                for (Achievement a : progress.progress().achievements()) {
                    if (a.achieved() && a.achievedOn() != null && !a.achievedOn().isBefore(today.minusDays(RECENT_DAYS))) {
                        drafts.add(new Draft("CAREER", "achievement:" + a.key(), "Achievement unlocked: " + a.title(),
                                a.description(), "/progress"));
                    }
                }
            } catch (RuntimeException unavailable) {
                log.debug("Career progress unavailable for notifications: {}", unavailable.getClass().getSimpleName());
            }
        }
        return drafts;
    }

    static List<String> enabled(Preferences p) {
        List<String> types = new ArrayList<>();
        if (p.jobMatches()) types.add("JOB_MATCH");
        if (p.followUps()) types.add("FOLLOW_UP");
        if (p.interviews()) types.add("INTERVIEW");
        if (p.learning()) types.add("LEARNING");
        if (p.career()) types.add("CAREER");
        return types;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
