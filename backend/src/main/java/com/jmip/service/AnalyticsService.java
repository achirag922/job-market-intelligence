package com.jmip.service;

import com.jmip.dto.CompanyResponse;
import com.jmip.dto.LocationResponse;
import com.jmip.dto.PagedResponse;
import com.jmip.dto.analytics.CompanyDemandResponse;
import com.jmip.dto.analytics.ExperienceDistributionResponse;
import com.jmip.dto.analytics.LocationDemandResponse;
import com.jmip.dto.analytics.OverviewResponse;
import com.jmip.repository.AnalyticsRepository;
import com.jmip.repository.CompanyRepository;
import com.jmip.repository.JobRepository;
import com.jmip.repository.LocationRepository;
import com.jmip.repository.SkillRepository;
import com.jmip.repository.projection.CompanyDemandRow;
import com.jmip.repository.projection.ExperienceCountRow;
import com.jmip.repository.projection.LocationDemandRow;
import com.jmip.service.analytics.ExperienceBucket;
import com.jmip.service.analytics.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Aggregate views over the ingested postings.
 *
 * <p>Every ranking is ordered inside its query, by posting count. Client supplied sorting
 * is deliberately not offered: these endpoints answer "what is most in demand", and that
 * question has one sensible ordering.
 */
@Service
@Transactional(readOnly = true)
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    private final JobRepository jobRepository;
    private final CompanyRepository companyRepository;
    private final LocationRepository locationRepository;
    private final SkillRepository skillRepository;
    private final AnalyticsRepository analyticsRepository;

    public AnalyticsService(JobRepository jobRepository,
                            CompanyRepository companyRepository,
                            LocationRepository locationRepository,
                            SkillRepository skillRepository,
                            AnalyticsRepository analyticsRepository) {
        this.jobRepository = jobRepository;
        this.companyRepository = companyRepository;
        this.locationRepository = locationRepository;
        this.skillRepository = skillRepository;
        this.analyticsRepository = analyticsRepository;
    }

    public OverviewResponse overview() {
        OverviewResponse overview = new OverviewResponse(
                jobRepository.count(),
                companyRepository.count(),
                skillRepository.count(),
                locationRepository.count());
        log.debug("Analytics overview: {}", overview);
        return overview;
    }

    /**
     * Distribution of required experience.
     *
     * <p>The bands always account for every posting: those stating no requirement are
     * reported under "Not specified" rather than quietly left out, so the counts can be
     * reconciled against the total.
     */
    public ExperienceDistributionResponse experienceDistribution() {
        Map<ExperienceBucket, Long> counts = new EnumMap<>(ExperienceBucket.class);
        for (ExperienceBucket bucket : ExperienceBucket.values()) {
            counts.put(bucket, 0L);
        }

        long totalJobs = 0;
        for (ExperienceCountRow row : analyticsRepository.countByExperienceMin()) {
            Integer years = row.experienceMin() == null ? null : row.experienceMin().intValue();
            counts.merge(ExperienceBucket.of(years), row.jobCount(), Long::sum);
            totalJobs += row.jobCount();
        }

        long total = totalJobs;
        List<ExperienceDistributionResponse.Bucket> buckets = Stream.of(ExperienceBucket.values())
                .map(bucket -> new ExperienceDistributionResponse.Bucket(
                        bucket.name(),
                        bucket.label(),
                        bucket.minYears(),
                        bucket.maxYearsExclusive(),
                        counts.get(bucket),
                        Metrics.percentageOf(counts.get(bucket), total)))
                .toList();

        log.debug("Experience distribution over {} jobs: {}", totalJobs, counts);
        return new ExperienceDistributionResponse(totalJobs, buckets);
    }

    public PagedResponse<LocationDemandResponse> locationDemand(int page, int size) {
        long totalJobs = jobRepository.count();
        Page<LocationDemandRow> rows = locationRepository.findLocationDemand(unsorted(page, size));

        List<LocationDemandResponse> content = new ArrayList<>(rows.getNumberOfElements());
        List<LocationDemandRow> pageRows = rows.getContent();
        for (int index = 0; index < pageRows.size(); index++) {
            LocationDemandRow row = pageRows.get(index);
            content.add(new LocationDemandResponse(
                    toLocation(row),
                    row.jobCount(),
                    Metrics.percentageOf(row.jobCount(), totalJobs),
                    Metrics.rank(page, size, index)));
        }
        return PagedResponse.of(content, rows);
    }

    public PagedResponse<CompanyDemandResponse> companyDemand(int page, int size) {
        long totalJobs = jobRepository.count();
        Page<CompanyDemandRow> rows = companyRepository.findCompanyDemand(unsorted(page, size));

        List<CompanyDemandResponse> content = new ArrayList<>(rows.getNumberOfElements());
        List<CompanyDemandRow> pageRows = rows.getContent();
        for (int index = 0; index < pageRows.size(); index++) {
            CompanyDemandRow row = pageRows.get(index);
            content.add(new CompanyDemandResponse(
                    new CompanyResponse(row.companyId(), row.name(), row.industry(), row.website()),
                    row.jobCount(),
                    Metrics.percentageOf(row.jobCount(), totalJobs),
                    Metrics.rank(page, size, index)));
        }
        return PagedResponse.of(content, rows);
    }

    /** Unsorted, because appending a client sort would fight the query's own ORDER BY. */
    private static Pageable unsorted(int page, int size) {
        return PageRequest.of(page, size);
    }

    private static LocationResponse toLocation(LocationDemandRow row) {
        String displayName = Stream.of(row.city(), row.state(), row.country())
                .filter(part -> part != null && !part.isBlank())
                .reduce((a, b) -> a + ", " + b)
                .orElse(row.country());
        return new LocationResponse(row.locationId(), row.city(), row.state(), row.country(), displayName);
    }
}
