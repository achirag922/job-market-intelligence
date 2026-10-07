package com.jmip.service.application;

import com.jmip.dto.SkillResponse;
import com.jmip.dto.application.ApplicationAnalysisResponse;
import com.jmip.dto.application.ApplicationInsightsResponse;
import com.jmip.dto.application.ApplicationInsightsResponse.Count;
import com.jmip.dto.application.ApplicationInsightsResponse.FollowUp;
import com.jmip.dto.application.ApplicationInsightsResponse.Funnel;
import com.jmip.dto.application.ApplicationInsightsResponse.MonthActivity;
import com.jmip.dto.resume.ResumeMatchResponse;
import com.jmip.entity.ApplicationStatus;
import com.jmip.entity.SavedJob;
import com.jmip.mapper.JobMapper;
import com.jmip.repository.ApplicationEventRepository;
import com.jmip.repository.ApplicationEventRepository.Event;
import com.jmip.repository.SavedJobRepository;
import com.jmip.service.auth.CurrentUser;
import com.jmip.service.resume.ResumeMatchService;
import com.jmip.service.resume.ResumeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * V8.5: application intelligence for the signed-in user, from their own saved jobs, status
 * history and current resume. Ownership comes from the session only; match scores are the
 * V8.3 ones, computed by {@link ResumeMatchService}.
 */
@Service
public class ApplicationIntelligenceService {

    static final int MIN_APPLICATIONS_FOR_RATES = 3;
    static final int TOP = 5;
    static final int FOLLOW_UPS = 10;
    static final int MONTHS = 12;

    private final SavedJobRepository savedJobs;
    private final ApplicationEventRepository events;
    private final ResumeService resumeService;
    private final ResumeMatchService matchService;
    private final JobMapper jobMapper;
    private final CurrentUser currentUser;
    private final Clock clock;

    public ApplicationIntelligenceService(SavedJobRepository savedJobs, ApplicationEventRepository events,
                                          ResumeService resumeService, ResumeMatchService matchService,
                                          JobMapper jobMapper, CurrentUser currentUser, Clock clock) {
        this.savedJobs = savedJobs;
        this.events = events;
        this.resumeService = resumeService;
        this.matchService = matchService;
        this.jobMapper = jobMapper;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /** Every tracked job, most recently changed first, with its match against the current resume. */
    @Transactional(readOnly = true)
    public List<ApplicationAnalysisResponse> applications() {
        List<SavedJob> rows = savedJobs.findByUserIdOrderByUpdatedAtDesc(currentUser.requireId());
        Matches matches = matches(rows);
        return rows.stream().map(saved -> analysis(saved, matches)).toList();
    }

    @Transactional(readOnly = true)
    public ApplicationInsightsResponse insights() {
        UUID owner = currentUser.requireId();
        List<SavedJob> rows = savedJobs.findByUserIdOrderByUpdatedAtDesc(owner);
        List<SavedJob> applied = rows.stream().filter(saved -> saved.getStatus() != ApplicationStatus.SAVED).toList();

        Map<ApplicationStatus, Long> statusCounts = new EnumMap<>(ApplicationStatus.class);
        for (ApplicationStatus status : ApplicationStatus.values()) {
            statusCounts.put(status, 0L);
        }
        rows.forEach(saved -> statusCounts.merge(saved.getStatus(), 1L, Long::sum));

        List<Event> history = events.findByUser(owner);
        Matches matches = matches(applied);
        return new ApplicationInsightsResponse(rows.size(), applied.size(), statusCounts,
                funnel(applied, history), activity(rows, history), activityNote(rows, history),
                average(applied, matches), scored(applied, matches), matchNote(applied, matches),
                top(applied.stream().flatMap(saved -> matches.missing(saved).stream()).map(SkillResponse::name)),
                top(applied.stream().map(saved -> saved.getJob().getCompany().getName())),
                top(applied.stream().map(saved -> saved.getJob().getTitle())),
                followUps(rows, true), followUps(rows, false));
    }

    // ------------------------------------------------------------------ funnel and activity

    private static Funnel funnel(List<SavedJob> applied, List<Event> history) {
        Map<UUID, Set<ApplicationStatus>> reached = new HashMap<>();
        history.forEach(event -> reached.computeIfAbsent(event.savedJobId(), id -> EnumSet.noneOf(ApplicationStatus.class))
                .add(event.status()));
        long interviewed = applied.stream().filter(saved -> reachedOrIs(saved, reached, ApplicationStatus.INTERVIEW)).count();
        long offers = applied.stream().filter(saved -> reachedOrIs(saved, reached, ApplicationStatus.OFFER)).count();
        int total = applied.size();
        boolean enough = total >= MIN_APPLICATIONS_FOR_RATES;
        return new Funnel(total, interviewed, offers, enough ? percent(interviewed, total) : null,
                enough ? percent(offers, total) : null, MIN_APPLICATIONS_FOR_RATES,
                enough ? null : "Conversion rates appear once you have at least " + MIN_APPLICATIONS_FOR_RATES + " applications.");
    }

    private static boolean reachedOrIs(SavedJob saved, Map<UUID, Set<ApplicationStatus>> reached, ApplicationStatus stage) {
        return saved.getStatus() == stage || reached.getOrDefault(saved.getId(), Set.of()).contains(stage);
    }

    /** The last {@value #MONTHS} months that had any activity. Reconstructed (backfilled) stage times are left out. */
    private List<MonthActivity> activity(List<SavedJob> rows, List<Event> history) {
        YearMonth from = YearMonth.now(clock.withZone(ZoneOffset.UTC)).minusMonths(MONTHS - 1L);
        Map<YearMonth, long[]> byMonth = new TreeMap<>();
        rows.forEach(saved -> {
            add(byMonth, from, saved.getSavedAt(), 0);
            add(byMonth, from, saved.getAppliedAt(), 1);
        });
        history.stream().filter(event -> !event.backfilled()).forEach(event -> {
            if (event.status() == ApplicationStatus.INTERVIEW) {
                add(byMonth, from, event.changedAt(), 2);
            } else if (event.status() == ApplicationStatus.OFFER) {
                add(byMonth, from, event.changedAt(), 3);
            }
        });
        return byMonth.entrySet().stream()
                .map(entry -> new MonthActivity(entry.getKey().toString(), entry.getValue()[0], entry.getValue()[1],
                        entry.getValue()[2], entry.getValue()[3]))
                .toList();
    }

    private String activityNote(List<SavedJob> rows, List<Event> history) {
        if (rows.isEmpty()) {
            return "Save jobs from the Job Explorer to see your activity here.";
        }
        return activity(rows, history).size() < 2 ? "Trends appear once your activity spans at least two months." : null;
    }

    private static void add(Map<YearMonth, long[]> byMonth, YearMonth from, OffsetDateTime at, int index) {
        if (at == null) {
            return;
        }
        YearMonth month = YearMonth.from(at.withOffsetSameInstant(ZoneOffset.UTC));
        if (!month.isBefore(from)) {
            byMonth.computeIfAbsent(month, key -> new long[4])[index]++;
        }
    }

    // ------------------------------------------------------------------ matching

    /** V8.3 matches of the current resume, by job id; empty without a processed resume. */
    private record Matches(boolean hasResume, Map<Long, ResumeMatchResponse> byJob) {

        ResumeMatchResponse of(SavedJob saved) {
            return byJob.get(saved.getJob().getId());
        }

        List<SkillResponse> missing(SavedJob saved) {
            ResumeMatchResponse match = of(saved);
            return match == null ? List.of() : match.missingSkills();
        }
    }

    private Matches matches(List<SavedJob> rows) {
        if (rows.isEmpty()) {
            return new Matches(false, Map.of());
        }
        return resumeService.currentProcessedResumeId()
                .map(resumeId -> new Matches(true, matchService.matchJobs(resumeId,
                        rows.stream().map(saved -> saved.getJob().getId()).toList())))
                .orElseGet(() -> new Matches(false, Map.of()));
    }

    private ApplicationAnalysisResponse analysis(SavedJob saved, Matches matches) {
        ResumeMatchResponse match = matches.of(saved);
        String note = !matches.hasResume() ? "Upload a resume to see how well it matches this job."
                : match == null ? null : match.matchNote();
        return new ApplicationAnalysisResponse(saved.getId(), jobMapper.toSummary(saved.getJob(), List.of()),
                saved.getStatus(), saved.getSavedAt(), saved.getAppliedAt(), saved.getNotes(), saved.getFollowUpOn(),
                saved.getFollowUpNote(),
                match == null || match.breakdown() == null ? null : match.breakdown().overallPercentage(),
                match == null ? null : match.matchPercentage(),
                match == null ? null : match.matchedSkills(),
                match == null ? null : match.missingSkills(), note);
    }

    private static Double average(List<SavedJob> applied, Matches matches) {
        List<Double> scores = scores(applied, matches);
        return scores.isEmpty() ? null : round(scores.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private static int scored(List<SavedJob> applied, Matches matches) {
        return scores(applied, matches).size();
    }

    private static List<Double> scores(List<SavedJob> applied, Matches matches) {
        return applied.stream().map(matches::of).filter(Objects::nonNull)
                .map(match -> match.breakdown() == null ? null : match.breakdown().overallPercentage())
                .filter(Objects::nonNull).toList();
    }

    private static String matchNote(List<SavedJob> applied, Matches matches) {
        if (applied.isEmpty()) {
            return "Move a saved job to Applied to see how your applications match.";
        }
        if (!matches.hasResume()) {
            return "Upload a resume to see how well your applications match.";
        }
        return scores(applied, matches).isEmpty() ? "None of your applied jobs lists skills to match against." : null;
    }

    // ------------------------------------------------------------------ rankings and follow-ups

    private static List<Count> top(java.util.stream.Stream<String> names) {
        return names.filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP)
                .map(entry -> new Count(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<FollowUp> followUps(List<SavedJob> rows, boolean upcoming) {
        LocalDate today = LocalDate.now(clock);
        return rows.stream()
                .filter(saved -> saved.getFollowUpOn() != null && (upcoming != saved.getFollowUpOn().isBefore(today)))
                .sorted(Comparator.comparing(SavedJob::getFollowUpOn))
                .limit(FOLLOW_UPS)
                .map(saved -> new FollowUp(saved.getId(), saved.getJob().getId(), saved.getJob().getTitle(),
                        saved.getJob().getCompany().getName(), saved.getStatus(), saved.getFollowUpOn(), saved.getFollowUpNote()))
                .toList();
    }

    private static double percent(long part, long whole) {
        return round(part * 100.0 / whole);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
