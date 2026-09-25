package com.jmip.dto.market;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jmip.dto.analytics.SkillTrendResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * V7.5 market intelligence responses. Every figure is counted from postings in the database;
 * nothing is estimated, converted or projected. Each response carries its {@link Scope}: which
 * postings it covers and which posting months they fall in, so older data is never mistaken
 * for current data.
 */
public final class MarketResponses {

    private MarketResponses() {
    }

    /**
     * @param from                 first posting date included, when a period was asked for
     * @param latestPostingInData  the newest posting date in the whole dataset, the anchor of any period
     * @param datedPostings        postings with a posting date; only these appear in monthly series
     * @param earliestMonth        first posting month covered, absent when no posting is dated
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Scope(String category, String location, String experience, LocalDate from,
                        LocalDate latestPostingInData, long postings, long datedPostings,
                        LocalDate earliestMonth, LocalDate latestMonth) {
    }

    // ------------------------------------------------------------------ salary

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SalaryResponse(Scope scope, long postingsWithSalary, List<SalaryFigure> byCurrency,
                                 List<SalaryFigure> byCategory, List<SalaryPoint> trend, List<String> notes) {
    }

    /** @param reliable at least the minimum sample of postings behind the figures */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SalaryFigure(String currency, String category, long postings, BigDecimal averageMin,
                               BigDecimal averageMax, BigDecimal lowestMin, BigDecimal highestMax, boolean reliable) {
    }

    public record SalaryPoint(LocalDate month, String currency, long postings, BigDecimal averageMin,
                              BigDecimal averageMax, boolean reliable) {
    }

    // ------------------------------------------------------------------ locations

    public record LocationResponse(Scope scope, long locationStated, long locationNotStated,
                                   List<LocationFigure> topLocations, List<String> notes) {
    }

    public record LocationFigure(Long locationId, String location, String country, long postings,
                                 double percentageOfPostings) {
    }

    // ------------------------------------------------------------------ work mode

    /** @param method how the work mode was read, since postings carry no field for it */
    public record RemoteResponse(Scope scope, List<ModeFigure> distribution, List<ModePoint> trend, String method,
                                 List<String> notes) {
    }

    public record ModeFigure(String mode, long postings, double percentageOfPostings) {
    }

    public record ModePoint(LocalDate month, long total, long remote, long hybrid, long onSite, long notStated) {
    }

    // ------------------------------------------------------------------ companies

    public record CompanyResponse(Scope scope, List<CompanyFigure> topCompanies, List<CompanySeries> trend,
                                  List<String> notes) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CompanyFigure(Long companyId, String company, String industry, long postings,
                                double percentageOfPostings) {
    }

    public record CompanySeries(Long companyId, String company, List<MonthCount> points) {
    }

    public record MonthCount(LocalDate month, long postings) {
    }

    // ------------------------------------------------------------------ skills

    /** @param trend from the stored monthly skill history; absent when the view is narrowed by a filter it cannot follow */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SkillResponse(Scope scope, List<SkillFigure> topSkills, SkillTrendResponse trend, List<String> notes) {
    }

    public record SkillFigure(Long skillId, String skill, String category, long postings, double percentageOfPostings,
                              int rank) {
    }
}
