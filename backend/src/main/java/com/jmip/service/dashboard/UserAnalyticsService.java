package com.jmip.service.dashboard;

import com.jmip.common.exception.InvalidRequestException;
import com.jmip.dto.SkillResponse;
import com.jmip.dto.dashboard.UserAnalyticsResponse;
import com.jmip.dto.dashboard.UserAnalyticsResponse.ActivityPoint;
import com.jmip.dto.dashboard.UserAnalyticsResponse.Funnel;
import com.jmip.dto.dashboard.UserAnalyticsResponse.InterviewPoint;
import com.jmip.dto.dashboard.UserAnalyticsResponse.Interviews;
import com.jmip.dto.dashboard.UserAnalyticsResponse.Kpis;
import com.jmip.dto.dashboard.UserAnalyticsResponse.Learning;
import com.jmip.dto.dashboard.UserAnalyticsResponse.LearningPoint;
import com.jmip.dto.dashboard.UserAnalyticsResponse.Portfolio;
import com.jmip.dto.dashboard.UserAnalyticsResponse.ResumePoint;
import com.jmip.dto.dashboard.UserAnalyticsResponse.SkillGap;
import com.jmip.dto.interview.InterviewDtos.Report;
import com.jmip.dto.resume.ResumeMatchResponse;
import com.jmip.dto.resume.ResumeResponse;
import com.jmip.entity.ApplicationStatus;
import com.jmip.entity.SavedJob;
import com.jmip.repository.ApplicationEventRepository;
import com.jmip.repository.ApplicationEventRepository.Event;
import com.jmip.repository.InterviewRepository;
import com.jmip.repository.InterviewRepository.SessionRow;
import com.jmip.repository.LearningRepository;
import com.jmip.repository.LearningRepository.ItemRow;
import com.jmip.repository.PortfolioRepository;
import com.jmip.repository.SavedJobRepository;
import com.jmip.service.auth.CurrentUser;
import com.jmip.service.interview.InterviewService;
import com.jmip.service.learning.LearningService;
import com.jmip.service.resume.ResumeMatchService;
import com.jmip.service.resume.ResumeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * V9.8: historical career analytics for the signed-in user, aggregated from data JMIP already keeps:
 * application status events (V8.5), resume versions scored with the V3/V8.3 skill match, completed
 * interviews (V9.6), the learning plan (V9.5) and the portfolio (V9.7). Everything is scoped to the
 * session's account, deterministic, and absent rather than invented when the data is not there.
 * The V7.7 dashboard shows the current state; this shows how it changed over time.
 */
@Service
public class UserAnalyticsService {

    private static final String MONTH_TEXT = "MONTH";
    private static final String COMPLETED_TEXT = "COMPLETED";

    static final int MAX_RESUME_VERSIONS = 12;
    static final int MAX_JOBS_COMPARED = 100;
    static final int TOP_MISSING = 10;
    private static final Set<ApplicationStatus> APPLIED_OR_LATER =
            Set.of(ApplicationStatus.APPLIED, ApplicationStatus.INTERVIEW, ApplicationStatus.OFFER);

    private final CurrentUser currentUser;
    private final ApplicationEventRepository events;
    private final SavedJobRepository savedJobs;
    private final ResumeService resumeService;
    private final ResumeMatchService matchService;
    private final InterviewRepository interviews;
    private final LearningRepository learningItems;
    private final LearningService learningService;
    private final PortfolioRepository portfolios;
    private final Clock clock;

    public UserAnalyticsService(CurrentUser currentUser, ApplicationEventRepository events, SavedJobRepository savedJobs,
                                ResumeService resumeService, ResumeMatchService matchService, InterviewRepository interviews,
                                LearningRepository learningItems, LearningService learningService,
                                PortfolioRepository portfolios, Clock clock) {
        this.currentUser = currentUser;
        this.events = events;
        this.savedJobs = savedJobs;
        this.resumeService = resumeService;
        this.matchService = matchService;
        this.interviews = interviews;
        this.learningItems = learningItems;
        this.learningService = learningService;
        this.portfolios = portfolios;
        this.clock = clock;
    }

    /** The range's first day (inclusive) and the step of its series. */
    record Window(String range, LocalDate from, LocalDate to, String bucket) {

        boolean contains(OffsetDateTime at) {
            if (at == null || from == null) {
                return false;
            }
            LocalDate day = day(at);
            return !day.isBefore(from) && !day.isAfter(to);
        }

        /** The equally long period just before this one; absent for ALL. */
        Window previous() {
            if ("ALL".equals(range) || from == null) {
                return null;
            }
            long days = ChronoUnit.DAYS.between(from, to) + 1;
            return new Window(range, from.minusDays(days), from.minusDays(1), bucket);
        }

        String key(LocalDate day) {
            return switch (bucket) {
                case "DAY" -> day.toString();
                case "WEEK" -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
                default -> YearMonth.from(day).toString();
            };
        }

        /** Every bucket label from the first day to the last, so gaps show as zero rather than disappearing. */
        List<String> labels() {
            List<String> labels = new ArrayList<>();
            if (from == null) {
                return labels;
            }
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                String key = key(day);
                if (labels.isEmpty() || !labels.get(labels.size() - 1).equals(key)) {
                    labels.add(key);
                }
            }
            return labels;
        }
    }

    static LocalDate day(OffsetDateTime at) {
        return at.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
    }

    /** @param earliest the first day with any data, used by ALL */
    static Window window(String range, LocalDate today, LocalDate earliest) {
        return switch (range) {
            case "7D" -> new Window(range, today.minusDays(6), today, "DAY");
            case "30D" -> new Window(range, today.minusDays(29), today, "DAY");
            case "90D" -> new Window(range, today.minusDays(89), today, "WEEK");
            case "1Y" -> new Window(range, today.minusYears(1).plusDays(1), today, MONTH_TEXT);
            case "ALL" -> {
                if (earliest == null) {
                    yield new Window(range, null, today, MONTH_TEXT);
                }
                long span = ChronoUnit.DAYS.between(earliest, today);
                yield new Window(range, earliest, today, span > 90 ? MONTH_TEXT : span > 31 ? "WEEK" : "DAY");
            }
            default -> throw new InvalidRequestException("range must be 7D, 30D, 90D, 1Y or ALL");
        };
    }

    @Transactional(readOnly = true)
    public UserAnalyticsResponse analytics(String requestedRange) {
        String range = requestedRange == null || requestedRange.isBlank() ? "30D" : requestedRange.strip().toUpperCase(Locale.ROOT);
        UUID owner = currentUser.requireId();
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));

        List<Event> history = events.findByUser(owner);
        List<SavedJob> saved = savedJobs.findByUserIdOrderByUpdatedAtDesc(owner);
        List<ResumeResponse> resumes = resumeService.list().stream().filter(r -> COMPLETED_TEXT.equals(r.status())).toList();
        List<SessionRow> completedInterviews = interviews.list(owner).stream()
                .filter(s -> COMPLETED_TEXT.equals(s.status()) && s.completedAt() != null)
                .sorted(Comparator.comparing(SessionRow::completedAt)).toList();
        List<ItemRow> items = learningItems.items(owner);

        LocalDate earliest = Stream.of(
                        history.stream().map(Event::changedAt),
                        saved.stream().map(SavedJob::getSavedAt),
                        resumes.stream().map(UserAnalyticsService::resumeDate),
                        completedInterviews.stream().map(SessionRow::completedAt),
                        items.stream().map(ItemRow::createdAt))
                .flatMap(Function.identity()).filter(Objects::nonNull).map(UserAnalyticsService::day)
                .min(Comparator.naturalOrder()).orElse(null);
        Window window = window(range, today, earliest);

        // Applications
        List<Event> inRange = history.stream().filter(e -> window.contains(e.changedAt())).toList();
        List<ActivityPoint> activity = activity(window, inRange);
        Funnel funnel = funnel(history, window);
        Map<String, Long> statusBreakdown = new LinkedHashMap<>();
        for (ApplicationStatus status : ApplicationStatus.values()) {
            statusBreakdown.put(status.name(), 0L);
        }
        List<SavedJob> savedInRange = saved.stream().filter(s -> window.contains(s.getSavedAt())).toList();
        savedInRange.forEach(s -> statusBreakdown.merge(s.getStatus().name(), 1L, Long::sum));

        // Resume versions and skill gaps, scored now against the saved jobs
        List<Long> jobIds = saved.stream().map(s -> s.getJob().getId()).distinct().limit(MAX_JOBS_COMPARED).toList();
        // V9.9: each resume is matched once, even when it is both a version in the range and the current one.
        Map<UUID, Map<Long, ResumeMatchResponse>> matched = new HashMap<>();
        List<ResumePoint> resumeTrend = resumeTrend(resumes, window, jobIds, matched);
        Double averageMatch = null;
        List<SkillGap> missing = List.of();
        UUID current = resumeService.currentProcessedResumeId().orElse(null);
        if (current != null && !jobIds.isEmpty()) {
            Map<Long, ResumeMatchResponse> matches = matched.computeIfAbsent(current, id -> matchService.matchJobs(id, jobIds));
            averageMatch = average(matches.values().stream().map(ResumeMatchResponse::matchPercentage));
            Set<Long> rangeJobs = savedInRange.stream().map(s -> s.getJob().getId()).collect(Collectors.toSet());
            missing = missingSkills(matches.entrySet().stream().filter(e -> rangeJobs.contains(e.getKey()))
                    .map(Map.Entry::getValue).toList());
        }

        Interviews interviewTrend = interviews(completedInterviews, window);
        Learning learning = learning(items, window);
        Portfolio portfolio = portfolios.find(owner)
                .map(p -> new Portfolio(true, p.visibility(), p.createdAt(), p.updatedAt(), p.publishedAt()))
                .orElse(new Portfolio(false, null, null, null, null));

        Kpis kpis = new Kpis(count(inRange, ApplicationStatus.SAVED), count(inRange, ApplicationStatus.APPLIED),
                count(inRange, ApplicationStatus.INTERVIEW), count(inRange, ApplicationStatus.OFFER), averageMatch,
                interviewTrend.completed(), interviewTrend.averageScore(), learning.completedInRange());

        List<String> insights = insights(window, history, funnel, resumeTrend, missing, savedInRange.size(), interviewTrend,
                learning);
        List<String> notes = new ArrayList<>(List.of(
                "Job search activity is measured by the jobs you saved and their status changes; searches themselves are not recorded.",
                "Match scores are computed now for each resume version against your saved jobs; past scores are not stored."));
        if (portfolio.exists()) {
            notes.add("Portfolio history is not recorded; only its creation, last update and publication dates are shown.");
        }
        return new UserAnalyticsResponse(range, window.from(), window.to(), window.bucket(), kpis, activity, funnel,
                statusBreakdown, resumeTrend, missing, interviewTrend, learning, portfolio, insights, notes);
    }

    // ------------------------------------------------------------------ applications

    static List<ActivityPoint> activity(Window window, List<Event> inRange) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        window.labels().forEach(label -> counts.put(label, new long[4]));
        for (Event event : inRange) {
            long[] row = counts.get(window.key(day(event.changedAt())));
            int index = switch (event.status()) {
                case SAVED -> 0;
                case APPLIED -> 1;
                case INTERVIEW -> 2;
                case OFFER -> 3;
                default -> -1;
            };
            if (row != null && index >= 0) {
                row[index]++;
            }
        }
        return counts.entrySet().stream()
                .map(e -> new ActivityPoint(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3]))
                .toList();
    }

    /** Jobs whose application started in the range (first APPLIED, INTERVIEW or OFFER event), and how far each got. */
    static Funnel funnel(List<Event> history, Window window) {
        Map<UUID, List<Event>> byJob = history.stream().collect(Collectors.groupingBy(Event::savedJobId));
        long applied = 0;
        long interviewed = 0;
        long offers = 0;
        for (List<Event> jobEvents : byJob.values()) {
            OffsetDateTime started = jobEvents.stream().filter(e -> APPLIED_OR_LATER.contains(e.status()))
                    .map(Event::changedAt).min(Comparator.naturalOrder()).orElse(null);
            if (started == null || !window.contains(started)) {
                continue;
            }
            applied++;
            boolean offer = jobEvents.stream().anyMatch(e -> e.status() == ApplicationStatus.OFFER);
            if (offer || jobEvents.stream().anyMatch(e -> e.status() == ApplicationStatus.INTERVIEW)) {
                interviewed++;
            }
            if (offer) {
                offers++;
            }
        }
        return new Funnel(applied, interviewed, offers, rate(interviewed, applied), rate(offers, interviewed));
    }

    private static long count(List<Event> events, ApplicationStatus status) {
        return events.stream().filter(e -> e.status() == status).count();
    }

    // ------------------------------------------------------------------ resume and skills

    private List<ResumePoint> resumeTrend(List<ResumeResponse> resumes, Window window, List<Long> jobIds,
                                          Map<UUID, Map<Long, ResumeMatchResponse>> matched) {
        List<ResumeResponse> versions = resumes.stream().filter(r -> window.contains(resumeDate(r)))
                .sorted(Comparator.comparing(UserAnalyticsService::resumeDate)).toList();
        if (versions.size() > MAX_RESUME_VERSIONS) {
            versions = versions.subList(versions.size() - MAX_RESUME_VERSIONS, versions.size());
        }
        List<ResumePoint> points = new ArrayList<>();
        for (ResumeResponse resume : versions) {
            Collection<ResumeMatchResponse> matches = jobIds.isEmpty() ? List.of()
                    : matched.computeIfAbsent(resume.id(), id -> matchService.matchJobs(id, jobIds)).values();
            int missing = (int) matches.stream().flatMap(m -> m.missingSkills().stream()).map(SkillResponse::name)
                    .distinct().count();
            points.add(new ResumePoint(resume.title() != null ? resume.title() : resume.fileName(), day(resumeDate(resume)),
                    resume.skills().size(), average(matches.stream().map(ResumeMatchResponse::matchPercentage)), missing,
                    matches.size()));
        }
        return points;
    }

    static List<SkillGap> missingSkills(List<ResumeMatchResponse> matches) {
        Map<String, Long> counts = new HashMap<>();
        matches.forEach(m -> m.missingSkills().stream().map(SkillResponse::name).distinct()
                .forEach(skill -> counts.merge(skill, 1L, Long::sum)));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_MISSING).map(e -> new SkillGap(e.getKey(), e.getValue())).toList();
    }

    private static OffsetDateTime resumeDate(ResumeResponse resume) {
        return resume.processedAt() != null ? resume.processedAt() : resume.uploadedAt();
    }

    // ------------------------------------------------------------------ interviews and learning

    private Interviews interviews(List<SessionRow> completed, Window window) {
        List<SessionRow> sessions = completed.stream().filter(s -> window.contains(s.completedAt())).toList();
        // V9.9: all their questions in one statement rather than one per session.
        Map<UUID, List<InterviewRepository.QuestionRow>> questions = interviews.questions(sessions.stream().map(SessionRow::id).toList());
        List<InterviewPoint> points = sessions.stream().map(s -> {
            Report scores = InterviewService.scores(questions.getOrDefault(s.id(), List.of()));
            return new InterviewPoint(day(s.completedAt()), s.jobTitle(), s.interviewType(), scores.overallScore(),
                    scores.technicalScore(), scores.behavioralScore());
        }).toList();
        List<Double> scored = points.stream().map(InterviewPoint::overall).filter(Objects::nonNull).toList();
        return new Interviews(points.size(), average(scored.stream()), scored.isEmpty() ? null : scored.get(0),
                scored.isEmpty() ? null : scored.get(scored.size() - 1), points);
    }

    private Learning learning(List<ItemRow> items, Window window) {
        int completed = (int) items.stream().filter(i -> COMPLETED_TEXT.equals(i.status())).count();
        int inProgress = (int) items.stream().filter(i -> "IN_PROGRESS".equals(i.status())).count();
        Map<String, long[]> counts = new LinkedHashMap<>();
        window.labels().forEach(label -> counts.put(label, new long[2]));
        long started = 0;
        long finished = 0;
        for (ItemRow item : items) {
            if (window.contains(item.startedAt())) {
                started++;
                counts.computeIfPresent(window.key(day(item.startedAt())), (k, row) -> { row[0]++; return row; });
            }
            if (window.contains(item.completedAt())) {
                finished++;
                counts.computeIfPresent(window.key(day(item.completedAt())), (k, row) -> { row[1]++; return row; });
            }
        }
        Integer targetSkills = null;
        Integer covered = null;
        try {
            var impact = learningService.plan().impact();
            targetSkills = impact.roadmapSkills();
            covered = impact.roadmapCompleted();
        } catch (RuntimeException unavailable) {
            // No goal or no roadmap data: the target comparison is simply absent.
        }
        return new Learning(items.size(), completed, inProgress, items.size() - completed - inProgress, started, finished,
                rate(completed, items.size()), targetSkills, covered,
                counts.entrySet().stream().map(e -> new LearningPoint(e.getKey(), e.getValue()[0], e.getValue()[1])).toList());
    }

    // ------------------------------------------------------------------ insights

    static List<String> insights(Window window, List<Event> history, Funnel funnel, List<ResumePoint> resumeTrend,
                                 List<SkillGap> missing, int savedInRange, Interviews interviews, Learning learning) {
        List<String> insights = new ArrayList<>();
        Window previous = window.previous();
        long applied = history.stream().filter(e -> e.status() == ApplicationStatus.APPLIED && window.contains(e.changedAt())).count();
        if (previous != null) {
            long before = history.stream().filter(e -> e.status() == ApplicationStatus.APPLIED
                    && previous.contains(e.changedAt())).count();
            if (applied + before > 0) {
                String change = applied > before ? "up from " : applied < before ? "down from " : "the same as ";
                insights.add("You applied to " + applied + " job" + (applied == 1 ? "" : "s") + " in this period, " + change
                        + before + " in the period before.");
            }
        }
        if (funnel.applied() > 0) {
            insights.add(funnel.interviewed() + " of " + funnel.applied() + " applications started in this period reached an "
                    + "interview (" + format(funnel.applyToInterviewRate()) + "%)"
                    + (funnel.interviewed() > 0 ? ", and " + funnel.offers() + " of those an offer (" + format(funnel.interviewToOfferRate()) + "%)." : "."));
        }
        List<ResumePoint> scoredVersions = resumeTrend.stream().filter(p -> p.averageMatch() != null).toList();
        if (scoredVersions.size() >= 2) {
            ResumePoint first = scoredVersions.get(0);
            ResumePoint last = scoredVersions.get(scoredVersions.size() - 1);
            String direction = last.averageMatch() > first.averageMatch() ? "rose"
                    : last.averageMatch() < first.averageMatch() ? "fell" : "was unchanged,";
            insights.add("Average skill match with your saved jobs " + direction + " from " + format(first.averageMatch())
                    + "% (“" + first.title() + "”) to " + format(last.averageMatch()) + "% (“" + last.title() + "”).");
        }
        if (!missing.isEmpty()) {
            SkillGap top = missing.get(0);
            insights.add(top.skill() + " is missing from your current resume in " + top.jobs() + " of the " + savedInRange
                    + " jobs you saved in this period.");
        }
        if (interviews.firstScore() != null && interviews.completed() >= 2) {
            insights.add("Your interview score went from " + format(interviews.firstScore()) + " to "
                    + format(interviews.latestScore()) + " out of 5 across " + interviews.completed() + " completed interviews.");
        }
        if (learning.items() > 0) {
            insights.add(learning.completed() + " of " + learning.items() + " learning items are completed ("
                    + format(learning.completionRate()) + "%); " + learning.completedInRange() + " completed in this period.");
        }
        return insights;
    }

    // ------------------------------------------------------------------ helpers

    private static Double rate(long part, long whole) {
        return whole == 0 ? null : round(part * 100.0 / whole);
    }

    private static Double average(Stream<Double> values) {
        List<Double> present = values.filter(Objects::nonNull).toList();
        return present.isEmpty() ? null : round(present.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static String format(Double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

}
