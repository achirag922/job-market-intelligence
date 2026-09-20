package com.jmip.service;

import com.jmip.dto.LocationResponse;
import com.jmip.dto.PagedResponse;
import com.jmip.mapper.JobMapper;
import com.jmip.repository.LocationRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@Transactional(readOnly = true)
public class LocationService {

    private static final SortWhitelist SORTABLE = SortWhitelist.of(Map.of(
            "country", "country",
            "city", "city",
            "state", "state",
            "createdAt", "createdAt"));

    private final LocationRepository locationRepository;
    private final JobMapper jobMapper;

    public LocationService(LocationRepository locationRepository, JobMapper jobMapper) {
        this.locationRepository = locationRepository;
        this.jobMapper = jobMapper;
    }

    public PagedResponse<LocationResponse> list(String countryFilter, Pageable pageable) {
        Pageable resolved = SORTABLE.apply(pageable);
        var page = countryFilter == null || countryFilter.isBlank()
                ? locationRepository.findAll(resolved)
                : locationRepository.findByCountryContainingIgnoreCase(countryFilter.trim(), resolved);
        return PagedResponse.of(page, jobMapper::toLocation);
    }
}
