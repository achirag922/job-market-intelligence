package com.jmip.service.analytics;

import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.dto.CompanyResponse;
import com.jmip.dto.LocationResponse;
import com.jmip.dto.analytics.CategoryDemandResponse;
import com.jmip.dto.analytics.CompanyDemandResponse;
import com.jmip.dto.analytics.EntitySkillResponse;
import com.jmip.dto.analytics.LocationDemandResponse;
import com.jmip.repository.AnalyticsRepository;
import com.jmip.repository.projection.CategoryCountRow;
import com.jmip.repository.projection.CompanyDemandRow;
import com.jmip.repository.projection.LocationDemandRow;
import com.jmip.repository.projection.SkillDemandRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Analytics over the V4 job categories.
 *
 * <p>Every breakdown is a share of that category's own postings, not of the whole corpus.
 * "Python appears in 80% of data engineering jobs" is the useful statement; the same
 * count as a share of every posting would say almost nothing.
 */
@Service
@Transactional(readOnly = true)
public class CategoryAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(CategoryAnalyticsService.class);

    private final AnalyticsRepository analyticsRepository;

    public CategoryAnalyticsService(AnalyticsRepository analyticsRepository) {
        this.analyticsRepository = analyticsRepository;
    }

    /** How postings are distributed across categories. */
    public List<CategoryDemandResponse> categoryDistribution() {
        long classified = analyticsRepository.countClassifiedJobs();
        List<CategoryCountRow> rows = analyticsRepository.countByCategory();

        log.debug("Category distribution over {} classified postings across {} categories",
                classified, rows.size());

        List<CategoryDemandResponse> result = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            CategoryCountRow row = rows.get(index);
            result.add(new CategoryDemandResponse(
                    row.category(),
                    row.jobCount(),
                    Metrics.percentageOf(row.jobCount(), classified),
                    index + 1));
        }
        return result;
    }

    public List<EntitySkillResponse> skillsForCategory(String category, int limit) {
        long categoryJobs = requireCategory(category);
        List<SkillDemandRow> rows =
                analyticsRepository.findSkillsForCategory(category, PageRequest.of(0, limit));

        List<EntitySkillResponse> result = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            SkillDemandRow row = rows.get(index);
            result.add(new EntitySkillResponse(
                    row.skillId(), row.name(), row.category(), row.jobCount(),
                    Metrics.percentageOf(row.jobCount(), categoryJobs), index + 1));
        }
        return result;
    }

    public List<LocationDemandResponse> locationsForCategory(String category, int limit) {
        long categoryJobs = requireCategory(category);
        List<LocationDemandRow> rows =
                analyticsRepository.findLocationsForCategory(category, PageRequest.of(0, limit));

        List<LocationDemandResponse> result = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            LocationDemandRow row = rows.get(index);
            result.add(new LocationDemandResponse(
                    toLocation(row), row.jobCount(),
                    Metrics.percentageOf(row.jobCount(), categoryJobs), index + 1));
        }
        return result;
    }

    public List<CompanyDemandResponse> companiesForCategory(String category, int limit) {
        long categoryJobs = requireCategory(category);
        List<CompanyDemandRow> rows =
                analyticsRepository.findCompaniesForCategory(category, PageRequest.of(0, limit));

        List<CompanyDemandResponse> result = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            CompanyDemandRow row = rows.get(index);
            result.add(new CompanyDemandResponse(
                    new CompanyResponse(row.companyId(), row.name(), row.industry(), row.website()),
                    row.jobCount(),
                    Metrics.percentageOf(row.jobCount(), categoryJobs), index + 1));
        }
        return result;
    }

    /**
     * @return how many postings the category holds
     * @throws ResourceNotFoundException when no posting carries it, so a mistyped category
     *                                   is a 404 rather than a silently empty list
     */
    private long requireCategory(String category) {
        long count = analyticsRepository.countJobsInCategory(category);
        if (count == 0) {
            throw ResourceNotFoundException.of("Job category", category);
        }
        return count;
    }

    private static LocationResponse toLocation(LocationDemandRow row) {
        String displayName = Stream.of(row.city(), row.state(), row.country())
                .filter(part -> part != null && !part.isBlank())
                .reduce((a, b) -> a + ", " + b)
                .orElse(row.country());
        return new LocationResponse(row.locationId(), row.city(), row.state(), row.country(), displayName);
    }
}
