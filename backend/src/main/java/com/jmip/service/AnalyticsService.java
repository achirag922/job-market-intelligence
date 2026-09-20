package com.jmip.service;

import com.jmip.dto.CompanyResponse;
import com.jmip.dto.LocationResponse;
import com.jmip.dto.PagedResponse;
import com.jmip.dto.analytics.CompanyDemandResponse;
import com.jmip.dto.analytics.LocationDemandResponse;
import com.jmip.dto.analytics.OverviewResponse;
import com.jmip.dto.analytics.SkillDemandResponse;
import com.jmip.repository.CompanyRepository;
import com.jmip.repository.JobRepository;
import com.jmip.repository.LocationRepository;
import com.jmip.repository.SkillRepository;
import com.jmip.repository.projection.LocationDemandRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Stream;

/**
 * Aggregate views over the ingested postings.
 *
 * <p>Every ranking is ordered inside its query, by posting count. Client supplied sorting
 * is deliberately not offered here: these endpoints answer "what is most in demand", and
 * that question has one sensible ordering.
 */
@Service
@Transactional(readOnly = true)
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    private final JobRepository jobRepository;
    private final CompanyRepository companyRepository;
    private final LocationRepository locationRepository;
    private final SkillRepository skillRepository;

    public AnalyticsService(JobRepository jobRepository,
                            CompanyRepository companyRepository,
                            LocationRepository locationRepository,
                            SkillRepository skillRepository) {
        this.jobRepository = jobRepository;
        this.companyRepository = companyRepository;
        this.locationRepository = locationRepository;
        this.skillRepository = skillRepository;
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

    public PagedResponse<SkillDemandResponse> skillDemand(int page, int size) {
        long totalJobs = jobRepository.count();
        var rows = skillRepository.findSkillDemand(unsorted(page, size));
        return PagedResponse.of(rows, row -> SkillService.toDemand(row, totalJobs));
    }

    public PagedResponse<LocationDemandResponse> locationDemand(int page, int size) {
        var rows = locationRepository.findLocationDemand(unsorted(page, size));
        return PagedResponse.of(rows, row -> new LocationDemandResponse(toLocation(row), row.jobCount()));
    }

    public PagedResponse<CompanyDemandResponse> companyDemand(int page, int size) {
        var rows = companyRepository.findCompanyDemand(unsorted(page, size));
        return PagedResponse.of(rows, row -> new CompanyDemandResponse(
                new CompanyResponse(row.companyId(), row.name(), row.industry(), row.website()),
                row.jobCount()));
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
