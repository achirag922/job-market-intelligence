package com.jmip.service.market;

import com.jmip.dto.analytics.SkillTrendResponse;
import com.jmip.dto.market.MarketResponses.ModePoint;
import com.jmip.dto.market.MarketResponses.SalaryPoint;
import com.jmip.dto.market.MarketTrendsResponse;
import com.jmip.dto.market.MarketTrendsResponse.LocationMove;
import com.jmip.dto.market.MarketTrendsResponse.ModeMove;
import com.jmip.dto.market.MarketTrendsResponse.Period;
import com.jmip.dto.market.MarketTrendsResponse.SalaryTrend;
import com.jmip.dto.market.MarketTrendsResponse.SkillMove;
import com.jmip.dto.market.MarketTrendsResponse.SkillTrends;
import com.jmip.dto.market.MarketTrendsResponse.Trend;
import com.jmip.dto.market.MarketTrendsResponse.VolumePoint;
import com.jmip.repository.MarketIntelligenceRepository;
import com.jmip.repository.MarketIntelligenceRepository.CountRow;
import com.jmip.repository.MarketIntelligenceRepository.SalaryRow;
import com.jmip.service.analytics.Metrics;
import com.jmip.service.analytics.SkillTrendService;
import com.jmip.service.market.TrendMath.Split;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * V8.8: career market trends over past posting months, for one role (a V4 job category) or the
 * whole market. Built on the V7.5 market queries and filter, and, for all roles, on the stored
 * V6.1 skill history; the arithmetic is {@link TrendMath}. Market data is not per user, so the
 * endpoint only needs a signed-in account.
 */
@Service
@Transactional(readOnly = true)
public class MarketTrendsService {

    static final int DEFAULT_MONTHS = 12;
    /** Fewer postings than this in the window and a trend is not computed. */
    static final int MIN_TREND_POSTINGS = 5;
    static final int MIN_FORECAST_POSTINGS = 10;
    static final int TOP_MOVERS = 5;
    static final int TOP_LOCATIONS = 5;
    /** As the V7.5 skill view: a skill needs this many postings in the window to have a trend. */
    static final int SKILL_MIN_POSTINGS = 3;
    /** Data older than this many months before today is called out as not current. */
    static final int STALE_AFTER_MONTHS = 2;
    static final List<String> MODES = List.of("REMOTE", "HYBRID", "ON_SITE");

    private final MarketIntelligenceService market;
    private final MarketIntelligenceRepository repository;
    private final SkillTrendService skillTrendService;
    private final Clock clock;

    public MarketTrendsService(MarketIntelligenceService market, MarketIntelligenceRepository repository,
                               SkillTrendService skillTrendService, Clock clock) {
        this.market = market;
        this.repository = repository;
        this.skillTrendService = skillTrendService;
        this.clock = clock;
    }

    public MarketTrendsResponse trends(String category, Integer months) {
        int span = months == null ? DEFAULT_MONTHS : months;
        MarketFilter role = market.filter(category, null, null, span);
        MarketFilter all = market.filter(null, null, null, span);
        LocalDate latest = repository.latestPostedDate();
        String roleName = role.category();
        List<String> notes = new ArrayList<>();

        if (latest == null || role.from() == null) {
            notes.add("No posting in JMIP has a posting date, so there is no history to show.");
            Trend none = TrendMath.insufficient("Postings per month", "No dated postings.");
            return new MarketTrendsResponse(roleName, new Period(span, null, null, 0, List.of(), null, source(roleName)),
                    List.of(), none, roleName == null ? null : none, emptySkills("No dated postings."), null, List.of(),
                    List.of(), List.of(), TrendMath.insufficientForecast("Insufficient data: no dated postings."), notes);
        }

        LocalDate to = latest.withDayOfMonth(1);
        List<LocalDate> window = new ArrayList<>();
        for (LocalDate month = role.from(); !month.isAfter(to); month = month.plusMonths(1)) {
            window.add(month);
        }
        Map<LocalDate, Long> allByMonth = byMonth(repository.postingsByMonth(all));
        Map<LocalDate, Long> roleByMonth = roleName == null ? allByMonth : byMonth(repository.postingsByMonth(role));
        // A month with no postings at all in the dataset is missing data, not zero demand.
        List<LocalDate> covered = window.stream().filter(allByMonth::containsKey).toList();
        List<LocalDate> missing = window.stream().filter(month -> !allByMonth.containsKey(month)).toList();
        Split split = TrendMath.split(covered);
        long rolePostings = TrendMath.sum(roleByMonth, covered);

        List<VolumePoint> volume = window.stream().map(month -> allByMonth.containsKey(month)
                ? new VolumePoint(month, roleByMonth.getOrDefault(month, 0L), allByMonth.get(month),
                        roleName == null ? null : Metrics.percentageOf(roleByMonth.getOrDefault(month, 0L), allByMonth.get(month)))
                : new VolumePoint(month, null, null, null)).toList();

        String volumeBasis = "Average postings per month with data";
        Trend volumeTrend = rolePostings < MIN_TREND_POSTINGS
                ? TrendMath.insufficient(volumeBasis, tooFew(rolePostings, MIN_TREND_POSTINGS))
                : TrendMath.percentTrend(TrendMath.average(roleByMonth, split.earlier()),
                        TrendMath.average(roleByMonth, split.recent()), split, volumeBasis);
        Trend shareTrend = null;
        if (roleName != null) {
            String shareBasis = "Share of all postings in JMIP, in percentage points";
            shareTrend = rolePostings < MIN_TREND_POSTINGS
                    ? TrendMath.insufficient(shareBasis, tooFew(rolePostings, MIN_TREND_POSTINGS))
                    : TrendMath.pointsTrend(share(roleByMonth, allByMonth, split.earlier()),
                            share(roleByMonth, allByMonth, split.recent()), split, shareBasis);
        }

        MarketTrendsResponse.Forecast forecast = rolePostings < MIN_FORECAST_POSTINGS
                ? TrendMath.insufficientForecast("Insufficient data: an estimate needs at least " + MIN_FORECAST_POSTINGS
                        + " postings in the period; this selection has " + rolePostings + ".")
                : TrendMath.forecast(covered, roleByMonth);

        if (!missing.isEmpty()) {
            notes.add(missing.size() + " month(s) in this period have no postings in JMIP at all; they are shown as gaps "
                    + "and left out of every comparison.");
        }
        staleNote(latest, LocalDate.now(clock)).ifPresent(notes::add);
        notes.add("Counts are postings in JMIP's dataset by posting month, not total hiring in the market.");

        return new MarketTrendsResponse(roleName,
                new Period(span, role.from(), to, covered.size(), missing, latest, source(roleName)),
                volume, volumeTrend, shareTrend,
                roleName == null ? skillsFromHistory(span) : skillsFromPostings(role, roleByMonth, split, rolePostings),
                salary(role, split), locations(role, split, covered), workModes(role, covered),
                workModeTrends(role, split, rolePostings), forecast, notes);
    }

    // ------------------------------------------------------------------ skills

    /** All roles: the stored V6.1 monthly skill history, through its own service. */
    private SkillTrends skillsFromHistory(int months) {
        SkillTrendResponse history = skillTrendService.trends(months, SKILL_MIN_POSTINGS, null, Integer.MAX_VALUE);
        String source = "Stored monthly skill history (all postings)";
        if (history.trends().isEmpty()) {
            return new SkillTrends(source, null, null, null, null, List.of(), List.of(),
                    "The stored skill history has too few months, or too few postings per skill, to compare periods.");
        }
        SkillTrendResponse.Window window = history.window();
        List<SkillMove> moves = history.trends().stream()
                .map(trend -> new SkillMove(trend.skillId(), trend.skill(), trend.category(), trend.jobCountInWindow(),
                        trend.earlierSharePercentage(), trend.recentSharePercentage(), trend.changeInPercentagePoints(),
                        TrendMath.word(trend.direction())))
                .toList();
        return skillTrends(source, first(window.earlierPeriods()), last(window.earlierPeriods()),
                first(window.recentPeriods()), last(window.recentPeriods()), moves, null);
    }

    /** One role: the same comparison and stable band, counted from that role's postings. */
    private SkillTrends skillsFromPostings(MarketFilter role, Map<LocalDate, Long> roleByMonth, Split split, long rolePostings) {
        String source = "Postings in this role, by posting month";
        if (!split.usable() || rolePostings < MIN_TREND_POSTINGS) {
            return emptySkills("Too few months or postings in this role to compare skill demand between periods.");
        }
        long earlierTotal = TrendMath.sum(roleByMonth, split.earlier());
        long recentTotal = TrendMath.sum(roleByMonth, split.recent());
        Map<Long, Map<LocalDate, Long>> bySkill = new LinkedHashMap<>();
        Map<Long, CountRow> names = new LinkedHashMap<>();
        for (CountRow row : repository.skillsByMonth(role)) {
            bySkill.computeIfAbsent(row.id(), id -> new LinkedHashMap<>()).put(row.month(), row.postings());
            names.putIfAbsent(row.id(), row);
        }
        List<LocalDate> covered = new ArrayList<>(split.earlier());
        covered.addAll(split.recent());
        List<SkillMove> moves = new ArrayList<>();
        bySkill.forEach((skillId, counts) -> {
            long total = TrendMath.sum(counts, covered);
            if (total < SKILL_MIN_POSTINGS) {
                return;
            }
            double earlierShare = Metrics.percentageOf(TrendMath.sum(counts, split.earlier()), earlierTotal);
            double recentShare = Metrics.percentageOf(TrendMath.sum(counts, split.recent()), recentTotal);
            double change = TrendMath.round1(recentShare - earlierShare);
            CountRow skill = names.get(skillId);
            moves.add(new SkillMove(skillId, skill.name(), skill.extra(), total, earlierShare, recentShare, change,
                    TrendMath.word(SkillTrendService.directionOf(change))));
        });
        return skillTrends(source, first(split.earlier()), last(split.earlier()), first(split.recent()),
                last(split.recent()), moves, moves.isEmpty() ? "No skill reached " + SKILL_MIN_POSTINGS
                        + " postings in this role over the period." : null);
    }

    private static SkillTrends skillTrends(String source, LocalDate earlierFrom, LocalDate earlierTo, LocalDate recentFrom,
                                           LocalDate recentTo, List<SkillMove> moves, String note) {
        List<SkillMove> growing = moves.stream().filter(move -> TrendMath.INCREASING.equals(move.direction()))
                .sorted(Comparator.comparingDouble(SkillMove::changeInPercentagePoints).reversed()
                        .thenComparing(SkillMove::skill))
                .limit(TOP_MOVERS).toList();
        List<SkillMove> declining = moves.stream().filter(move -> TrendMath.DECREASING.equals(move.direction()))
                .sorted(Comparator.comparingDouble(SkillMove::changeInPercentagePoints).thenComparing(SkillMove::skill))
                .limit(TOP_MOVERS).toList();
        return new SkillTrends(source, earlierFrom, earlierTo, recentFrom, recentTo, growing, declining, note);
    }

    private static SkillTrends emptySkills(String note) {
        return new SkillTrends(null, null, null, null, null, List.of(), List.of(), note);
    }

    // ------------------------------------------------------------------ salary, locations, work modes

    /** The currency most postings state, never combined with another; the midpoint of stated min and max. */
    private SalaryTrend salary(MarketFilter role, Split split) {
        Map<String, List<SalaryRow>> byCurrency = repository.salaries(role, false, true).stream()
                .collect(Collectors.groupingBy(SalaryRow::currency, LinkedHashMap::new, Collectors.toList()));
        if (byCurrency.isEmpty()) {
            return null;
        }
        String currency = byCurrency.entrySet().stream()
                .max(Comparator.comparingLong((Map.Entry<String, List<SalaryRow>> e) ->
                        e.getValue().stream().mapToLong(SalaryRow::postings).sum()).thenComparing(Map.Entry::getKey,
                        Comparator.reverseOrder()))
                .map(Map.Entry::getKey).orElseThrow();
        Map<LocalDate, SalaryRow> rows = byCurrency.get(currency).stream()
                .collect(Collectors.toMap(SalaryRow::month, row -> row, (a, b) -> a, LinkedHashMap::new));
        List<SalaryPoint> series = rows.values().stream()
                .map(row -> new SalaryPoint(row.month(), currency, row.postings(), scale(row.averageMin()), scale(row.averageMax()),
                        row.postings() >= MarketIntelligenceService.MIN_SALARY_SAMPLE))
                .toList();
        String basis = "Average stated salary midpoint in " + currency;
        long earlierCount = count(rows, split.earlier());
        long recentCount = count(rows, split.recent());
        Trend trend = !split.usable() || earlierCount < MarketIntelligenceService.MIN_SALARY_SAMPLE
                || recentCount < MarketIntelligenceService.MIN_SALARY_SAMPLE
                ? TrendMath.insufficient(basis, "Fewer than " + MarketIntelligenceService.MIN_SALARY_SAMPLE
                        + " postings state a salary in " + currency + " in one of the two periods.")
                : TrendMath.percentTrend(midpoint(rows, split.earlier()), midpoint(rows, split.recent()), split, basis);
        String note = byCurrency.size() > 1 ? "Only " + currency + ", the currency most postings state; other currencies "
                + "are never converted or combined." : null;
        return new SalaryTrend(currency, series, trend, note);
    }

    private List<LocationMove> locations(MarketFilter role, Split split, List<LocalDate> covered) {
        List<CountRow> top = repository.locations(role).stream().filter(row -> row.id() != null).limit(TOP_LOCATIONS).toList();
        Map<Long, Map<LocalDate, Long>> counts = new LinkedHashMap<>();
        for (CountRow row : repository.locationsByMonth(role, top.stream().map(CountRow::id).toList())) {
            counts.computeIfAbsent(row.id(), id -> new LinkedHashMap<>()).put(row.month(), row.postings());
        }
        String basis = "Average postings per month with data";
        return top.stream().map(location -> {
            Map<LocalDate, Long> series = counts.getOrDefault(location.id(), Map.of());
            long total = TrendMath.sum(series, covered);
            Trend trend = total < MIN_TREND_POSTINGS
                    ? TrendMath.insufficient(basis, tooFew(total, MIN_TREND_POSTINGS))
                    : TrendMath.percentTrend(TrendMath.average(series, split.earlier()),
                            TrendMath.average(series, split.recent()), split, basis);
            return new LocationMove(location.id(), location.name(), location.postings(), trend);
        }).toList();
    }

    private List<ModePoint> workModes(MarketFilter role, List<LocalDate> covered) {
        Map<LocalDate, Map<String, Long>> byMonth = modesByMonth(role);
        return covered.stream().map(month -> {
            Map<String, Long> counts = byMonth.getOrDefault(month, Map.of());
            return new ModePoint(month, counts.values().stream().mapToLong(Long::longValue).sum(),
                    counts.getOrDefault("REMOTE", 0L), counts.getOrDefault("HYBRID", 0L),
                    counts.getOrDefault("ON_SITE", 0L), counts.getOrDefault("NOT_STATED", 0L));
        }).toList();
    }

    private List<ModeMove> workModeTrends(MarketFilter role, Split split, long rolePostings) {
        Map<LocalDate, Map<String, Long>> byMonth = modesByMonth(role);
        return MODES.stream().map(mode -> {
            String basis = "Share of postings describing themselves as " + mode.replace('_', '-').toLowerCase(java.util.Locale.ROOT)
                    + ", in percentage points";
            if (rolePostings < MIN_TREND_POSTINGS) {
                return new ModeMove(mode, TrendMath.insufficient(basis, tooFew(rolePostings, MIN_TREND_POSTINGS)));
            }
            return new ModeMove(mode, TrendMath.pointsTrend(modeShare(byMonth, mode, split.earlier()),
                    modeShare(byMonth, mode, split.recent()), split, basis));
        }).toList();
    }

    private Map<LocalDate, Map<String, Long>> modesByMonth(MarketFilter role) {
        return com.jmip.common.RequestMemo.get("marketTrendModes:" + role, () -> {
            Map<LocalDate, Map<String, Long>> byMonth = new LinkedHashMap<>();
            for (CountRow row : repository.workModes(role, true)) {
                byMonth.computeIfAbsent(row.month(), month -> new LinkedHashMap<>()).put(row.name(), row.postings());
            }
            return byMonth;
        });
    }

    // ------------------------------------------------------------------ helpers

    /** Called out when the newest posting is older than {@value #STALE_AFTER_MONTHS} months before today. */
    static java.util.Optional<String> staleNote(LocalDate latestPosted, LocalDate today) {
        LocalDate latestMonth = latestPosted.withDayOfMonth(1);
        if (latestMonth.isBefore(today.withDayOfMonth(1).minusMonths(STALE_AFTER_MONTHS))) {
            return java.util.Optional.of("The newest posting in JMIP is from " + latestMonth.toString().substring(0, 7)
                    + ". These figures describe the market up to then, not today, and any estimate starts from that month.");
        }
        return java.util.Optional.empty();
    }

    private static String source(String category) {
        return category == null
                ? "All postings in JMIP by posting month; skill trends from the stored monthly skill history."
                : "Postings in JMIP classified as " + category + ", by posting month, compared with all postings.";
    }

    private static String tooFew(long postings, int minimum) {
        return "Insufficient data: " + postings + " posting(s) in this period; at least " + minimum + " are needed.";
    }

    private static Map<LocalDate, Long> byMonth(List<CountRow> rows) {
        Map<LocalDate, Long> counts = new LinkedHashMap<>();
        rows.forEach(row -> counts.put(row.month(), row.postings()));
        return counts;
    }

    private static double share(Map<LocalDate, Long> part, Map<LocalDate, Long> whole, List<LocalDate> months) {
        return Metrics.percentageOf(TrendMath.sum(part, months), TrendMath.sum(whole, months));
    }

    private static double modeShare(Map<LocalDate, Map<String, Long>> byMonth, String mode, List<LocalDate> months) {
        long part = 0;
        long whole = 0;
        for (LocalDate month : months) {
            Map<String, Long> counts = byMonth.getOrDefault(month, Map.of());
            part += counts.getOrDefault(mode, 0L);
            whole += counts.values().stream().mapToLong(Long::longValue).sum();
        }
        return Metrics.percentageOf(part, whole);
    }

    private static long count(Map<LocalDate, SalaryRow> rows, List<LocalDate> months) {
        return months.stream().map(rows::get).filter(java.util.Objects::nonNull).mapToLong(SalaryRow::postings).sum();
    }

    /** The posting-weighted mean of each month's midpoint of average minimum and maximum. */
    private static double midpoint(Map<LocalDate, SalaryRow> rows, List<LocalDate> months) {
        double weighted = 0;
        long postings = 0;
        for (LocalDate month : months) {
            SalaryRow row = rows.get(month);
            if (row == null || row.averageMin() == null) {
                continue;
            }
            BigDecimal max = row.averageMax() == null ? row.averageMin() : row.averageMax();
            double mid = (row.averageMin().doubleValue() + max.doubleValue()) / 2.0;
            weighted += mid * row.postings();
            postings += row.postings();
        }
        return postings == 0 ? 0 : weighted / postings;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(0, RoundingMode.HALF_UP);
    }

    private static LocalDate first(List<LocalDate> months) {
        return months.isEmpty() ? null : months.get(0);
    }

    private static LocalDate last(List<LocalDate> months) {
        return months.isEmpty() ? null : months.get(months.size() - 1);
    }
}
