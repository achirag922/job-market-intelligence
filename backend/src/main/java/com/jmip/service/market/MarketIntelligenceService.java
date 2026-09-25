package com.jmip.service.market;

import com.jmip.dto.analytics.SkillTrendResponse;
import com.jmip.dto.market.MarketResponses.CompanyFigure;
import com.jmip.dto.market.MarketResponses.CompanyResponse;
import com.jmip.dto.market.MarketResponses.CompanySeries;
import com.jmip.dto.market.MarketResponses.LocationFigure;
import com.jmip.dto.market.MarketResponses.LocationResponse;
import com.jmip.dto.market.MarketResponses.ModeFigure;
import com.jmip.dto.market.MarketResponses.ModePoint;
import com.jmip.dto.market.MarketResponses.MonthCount;
import com.jmip.dto.market.MarketResponses.RemoteResponse;
import com.jmip.dto.market.MarketResponses.SalaryFigure;
import com.jmip.dto.market.MarketResponses.SalaryPoint;
import com.jmip.dto.market.MarketResponses.SalaryResponse;
import com.jmip.dto.market.MarketResponses.Scope;
import com.jmip.dto.market.MarketResponses.SkillFigure;
import com.jmip.dto.market.MarketResponses.SkillResponse;
import com.jmip.repository.MarketIntelligenceRepository;
import com.jmip.repository.MarketIntelligenceRepository.CountRow;
import com.jmip.repository.MarketIntelligenceRepository.SalaryRow;
import com.jmip.repository.MarketIntelligenceRepository.Window;
import com.jmip.service.analytics.ExperienceBucket;
import com.jmip.service.analytics.Metrics;
import com.jmip.service.analytics.SkillTrendService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * V7.5: salary, location, work-mode, company and skill views of the job market, counted from
 * the postings JMIP holds. Monthly series follow the posting month, like the V6.1 skill
 * history; skill trends reuse that history itself. Nothing here forecasts.
 */
@Service
@Transactional(readOnly = true)
public class MarketIntelligenceService {

    /** Fewer postings than this behind a salary figure and it is marked unreliable. */
    static final int MIN_SALARY_SAMPLE = 3;
    static final int TOP_LOCATIONS = 10;
    static final int TOP_COMPANIES = 10;
    static final int COMPANY_SERIES = 5;
    static final int TOP_SKILLS = 15;
    /** The skill-trend defaults of the public trends endpoint. */
    static final int DEFAULT_TREND_MONTHS = 6;
    static final int TREND_MIN_JOBS = 3;
    static final int TREND_LIMIT = 10;

    static final List<String> MODES = List.of("REMOTE", "HYBRID", "ON_SITE", "NOT_STATED");
    static final String WORK_MODE_METHOD = "Read from each posting's description: \"hybrid\" means HYBRID, otherwise "
            + "\"remote\" means REMOTE, otherwise \"on-site\", \"onsite\" or \"in office\" means ON_SITE. Anything else "
            + "is NOT_STATED; postings do not carry a work-mode field.";

    private final MarketIntelligenceRepository repository;
    private final SkillTrendService skillTrendService;

    public MarketIntelligenceService(MarketIntelligenceRepository repository, SkillTrendService skillTrendService) {
        this.repository = repository;
        this.skillTrendService = skillTrendService;
    }

    /** Builds the filter; a period of N months ends at the newest posting in the data, not today. */
    public MarketFilter filter(String category, String location, String experience, Integer months) {
        LocalDate from = null;
        if (months != null) {
            LocalDate latest = repository.latestPostedDate();
            from = latest == null ? null : latest.withDayOfMonth(1).minusMonths(months - 1L);
        }
        return new MarketFilter(blankToNull(category), blankToNull(location),
                experience == null || experience.isBlank() ? null : ExperienceBucket.fromSlug(experience), from);
    }

    public SalaryResponse salary(MarketFilter filter) {
        Scope scope = scope(filter);
        List<SalaryFigure> byCurrency = repository.salaries(filter, false, false).stream().map(this::figure).toList();
        List<SalaryFigure> byCategory = repository.salaries(filter, true, false).stream()
                .filter(row -> row.category() != null).map(this::figure).toList();
        List<SalaryPoint> trend = repository.salaries(filter, false, true).stream()
                .map(row -> new SalaryPoint(row.month(), row.currency(), row.postings(), round(row.averageMin()),
                        round(row.averageMax()), row.postings() >= MIN_SALARY_SAMPLE))
                .toList();
        long withSalary = byCurrency.stream().mapToLong(SalaryFigure::postings).sum();

        List<String> notes = new ArrayList<>();
        if (withSalary == 0) {
            notes.add("No posting in this selection states a salary, so there are no salary figures.");
        } else {
            notes.add("Salaries are shown as stated, per currency. Currencies are never converted or combined, "
                    + "and postings that state no salary are left out.");
            notes.add("Figures from fewer than " + MIN_SALARY_SAMPLE + " postings are marked as not reliable.");
        }
        historyNote(scope, notes);
        return new SalaryResponse(scope, withSalary, byCurrency, byCategory, trend, notes);
    }

    public LocationResponse locations(MarketFilter filter) {
        Scope scope = scope(filter);
        List<CountRow> rows = repository.locations(filter);
        long notStated = rows.stream().filter(row -> row.id() == null).mapToLong(CountRow::postings).sum();
        List<LocationFigure> top = rows.stream().filter(row -> row.id() != null).limit(TOP_LOCATIONS)
                .map(row -> new LocationFigure(row.id(), row.name(), row.extra(), row.postings(),
                        Metrics.percentageOf(row.postings(), scope.postings())))
                .toList();
        List<String> notes = new ArrayList<>();
        if (scope.postings() == 0) {
            notes.add("No postings match this selection.");
        }
        if (notStated > 0) {
            notes.add(notStated + " posting(s) state no location; the remote-work view shows how many describe themselves as remote.");
        }
        return new LocationResponse(scope, scope.postings() - notStated, notStated, top, notes);
    }

    public RemoteResponse remote(MarketFilter filter) {
        Scope scope = scope(filter);
        Map<String, Long> totals = repository.workModes(filter, false).stream()
                .collect(Collectors.toMap(CountRow::name, CountRow::postings));
        List<ModeFigure> distribution = MODES.stream()
                .map(mode -> new ModeFigure(mode, totals.getOrDefault(mode, 0L),
                        Metrics.percentageOf(totals.getOrDefault(mode, 0L), scope.postings())))
                .toList();

        Map<LocalDate, Map<String, Long>> byMonth = new LinkedHashMap<>();
        for (CountRow row : repository.workModes(filter, true)) {
            byMonth.computeIfAbsent(row.month(), month -> new LinkedHashMap<>()).put(row.name(), row.postings());
        }
        List<ModePoint> trend = months(scope).stream().map(month -> {
            Map<String, Long> counts = byMonth.getOrDefault(month, Map.of());
            return new ModePoint(month, counts.values().stream().mapToLong(Long::longValue).sum(),
                    counts.getOrDefault("REMOTE", 0L), counts.getOrDefault("HYBRID", 0L),
                    counts.getOrDefault("ON_SITE", 0L), counts.getOrDefault("NOT_STATED", 0L));
        }).toList();

        List<String> notes = new ArrayList<>();
        if (scope.postings() == 0) {
            notes.add("No postings match this selection.");
        }
        historyNote(scope, notes);
        return new RemoteResponse(scope, distribution, trend, WORK_MODE_METHOD, notes);
    }

    public CompanyResponse companies(MarketFilter filter) {
        Scope scope = scope(filter);
        List<CompanyFigure> top = repository.companies(filter, TOP_COMPANIES).stream()
                .map(row -> new CompanyFigure(row.id(), row.name(), row.extra(), row.postings(),
                        Metrics.percentageOf(row.postings(), scope.postings())))
                .toList();
        List<CompanyFigure> charted = top.stream().limit(COMPANY_SERIES).toList();
        Map<Long, Map<LocalDate, Long>> counts = new LinkedHashMap<>();
        for (CountRow row : repository.companiesByMonth(filter, charted.stream().map(CompanyFigure::companyId).toList())) {
            counts.computeIfAbsent(row.id(), id -> new LinkedHashMap<>()).put(row.month(), row.postings());
        }
        List<LocalDate> months = months(scope);
        List<CompanySeries> trend = charted.stream()
                .map(company -> new CompanySeries(company.companyId(), company.company(), months.stream()
                        .map(month -> new MonthCount(month, counts.getOrDefault(company.companyId(), Map.of())
                                .getOrDefault(month, 0L)))
                        .toList()))
                .toList();

        List<String> notes = new ArrayList<>();
        if (scope.postings() == 0) {
            notes.add("No postings match this selection.");
        }
        notes.add("Counts are postings in JMIP's dataset per posting month, not a company's total hiring.");
        historyNote(scope, notes);
        return new CompanyResponse(scope, top, trend, notes);
    }

    /**
     * Current demand among the filtered postings, and the trend from the stored skill history.
     * That history spans all postings, so it is only offered when no category, location or
     * experience filter narrows the view.
     */
    public SkillResponse skills(MarketFilter filter, Integer months) {
        Scope scope = scope(filter);
        List<CountRow> rows = repository.skills(filter, TOP_SKILLS);
        List<SkillFigure> top = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            CountRow row = rows.get(index);
            top.add(new SkillFigure(row.id(), row.name(), row.extra(), row.postings(),
                    Metrics.percentageOf(row.postings(), scope.postings()), index + 1));
        }

        List<String> notes = new ArrayList<>();
        SkillTrendResponse trend = null;
        if (filter.category() != null || filter.location() != null || filter.experience() != null) {
            notes.add("Skill trends come from the stored monthly skill history, which covers all postings. "
                    + "Clear the category, location and experience filters to see them.");
        } else {
            trend = skillTrendService.trends(months == null ? DEFAULT_TREND_MONTHS : months, TREND_MIN_JOBS, null, TREND_LIMIT);
            if (trend.trends().isEmpty()) {
                notes.add("The stored skill history has too few months, or too few postings per skill, to compare periods.");
            }
        }
        if (scope.postings() == 0) {
            notes.add("No postings match this selection.");
        }
        return new SkillResponse(scope, top, trend, notes);
    }

    // ------------------------------------------------------------------ helpers

    private Scope scope(MarketFilter filter) {
        Window window = repository.window(filter);
        return new Scope(filter.category(), filter.location(),
                filter.experience() == null ? null : filter.experience().label(), filter.from(),
                repository.latestPostedDate(), window.postings(), window.datedPostings(), window.earliest(), window.latest());
    }

    /** Every posting month from the first to the last covered, so a quiet month shows as zero. */
    static List<LocalDate> months(Scope scope) {
        List<LocalDate> months = new ArrayList<>();
        if (scope.earliestMonth() == null) {
            return months;
        }
        for (LocalDate month = scope.earliestMonth(); !month.isAfter(scope.latestMonth()); month = month.plusMonths(1)) {
            months.add(month);
        }
        return months;
    }

    private static void historyNote(Scope scope, List<String> notes) {
        List<LocalDate> months = months(scope);
        if (months.size() < 2) {
            notes.add("There is not enough dated history for a trend (" + months.size() + " posting month).");
        }
        if (scope.datedPostings() < scope.postings()) {
            notes.add((scope.postings() - scope.datedPostings()) + " posting(s) have no posting date and appear in totals only.");
        }
    }

    private SalaryFigure figure(SalaryRow row) {
        return new SalaryFigure(row.currency(), row.category(), row.postings(), round(row.averageMin()),
                round(row.averageMax()), row.lowestMin(), row.highestMax(), row.postings() >= MIN_SALARY_SAMPLE);
    }

    private static BigDecimal round(BigDecimal value) {
        return value == null ? null : value.setScale(0, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
