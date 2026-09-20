package com.jmip.service.analytics;

import com.jmip.dto.PagedResponse;
import com.jmip.dto.analytics.EntitySkillResponse;
import com.jmip.dto.analytics.SkillAnalyticsResponse;
import com.jmip.dto.analytics.SkillDemandResponse;
import com.jmip.repository.AnalyticsRepository;
import com.jmip.repository.projection.SkillDemandRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@Transactional(readOnly = true)
public class SkillAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(SkillAnalyticsService.class);

    private final AnalyticsRepository analyticsRepository;

    public SkillAnalyticsService(AnalyticsRepository analyticsRepository) {
        this.analyticsRepository = analyticsRepository;
    }

    /**
     * Skill demand within the given scope.
     *
     * <p>Percentages are taken against the postings the filters match, not against every
     * posting in the database. Filtering to one city and then reporting each skill as a
     * share of the global total would understate every figure, and the numbers would not
     * mean what the label says.
     */
    public SkillAnalyticsResponse skillDemand(String location,
                                              LocalDate fromDate,
                                              LocalDate toDate,
                                              String title,
                                              int page,
                                              int size) {
        String locationPattern = containsPattern(location);
        String titlePattern = containsPattern(title);

        long totalInScope = analyticsRepository.countJobsInScope(locationPattern, fromDate, toDate, titlePattern);
        Page<SkillDemandRow> rows = analyticsRepository.findSkillDemand(
                locationPattern, fromDate, toDate, titlePattern, PageRequest.of(page, size));

        log.debug("Skill demand: {} skills over {} jobs in scope (location={}, from={}, to={}, title={})",
                rows.getTotalElements(), totalInScope, location, fromDate, toDate, title);

        List<SkillDemandResponse> content = new ArrayList<>(rows.getNumberOfElements());
        List<SkillDemandRow> pageRows = rows.getContent();
        for (int index = 0; index < pageRows.size(); index++) {
            SkillDemandRow row = pageRows.get(index);
            content.add(new SkillDemandResponse(
                    row.skillId(),
                    row.name(),
                    row.category(),
                    row.jobCount(),
                    Metrics.percentageOf(row.jobCount(), totalInScope),
                    Metrics.rank(page, size, index)));
        }

        return new SkillAnalyticsResponse(
                new SkillAnalyticsResponse.Scope(totalInScope, normalise(location), fromDate, toDate, normalise(title)),
                PagedResponse.of(content, rows));
    }

    /** Skills most asked for by one company, as a share of that company's own postings. */
    public List<EntitySkillResponse> skillsForCompany(Long companyId, int limit) {
        long companyJobs = analyticsRepository.countJobsForCompany(companyId);
        return toEntitySkills(analyticsRepository.findSkillsForCompany(companyId, PageRequest.of(0, limit)),
                companyJobs);
    }

    /** Skills most asked for in one location, as a share of that location's own postings. */
    public List<EntitySkillResponse> skillsForLocation(Long locationId, int limit) {
        long locationJobs = analyticsRepository.countJobsForLocation(locationId);
        return toEntitySkills(analyticsRepository.findSkillsForLocation(locationId, PageRequest.of(0, limit)),
                locationJobs);
    }

    private List<EntitySkillResponse> toEntitySkills(List<SkillDemandRow> rows, long total) {
        List<EntitySkillResponse> result = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            SkillDemandRow row = rows.get(index);
            result.add(new EntitySkillResponse(
                    row.skillId(),
                    row.name(),
                    row.category(),
                    row.jobCount(),
                    Metrics.percentageOf(row.jobCount(), total),
                    index + 1));
        }
        return result;
    }

    /** Null when absent, so the query's {@code :param is null} branch disables the filter. */
    private static String containsPattern(String value) {
        String trimmed = normalise(value);
        return trimmed == null ? null : "%" + trimmed.toLowerCase(Locale.ROOT) + "%";
    }

    private static String normalise(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
