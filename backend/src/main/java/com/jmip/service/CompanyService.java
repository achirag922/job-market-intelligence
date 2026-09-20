package com.jmip.service;

import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.dto.CompanyDetailResponse;
import com.jmip.dto.CompanyResponse;
import com.jmip.dto.PagedResponse;
import com.jmip.mapper.JobMapper;
import com.jmip.repository.CompanyRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@Transactional(readOnly = true)
public class CompanyService {

    private static final SortWhitelist SORTABLE = SortWhitelist.of(Map.of(
            "name", "name",
            "industry", "industry",
            "createdAt", "createdAt"));

    private final CompanyRepository companyRepository;
    private final JobMapper jobMapper;

    public CompanyService(CompanyRepository companyRepository, JobMapper jobMapper) {
        this.companyRepository = companyRepository;
        this.jobMapper = jobMapper;
    }

    public PagedResponse<CompanyResponse> list(String nameFilter, Pageable pageable) {
        Pageable resolved = SORTABLE.apply(pageable);
        var page = nameFilter == null || nameFilter.isBlank()
                ? companyRepository.findAll(resolved)
                : companyRepository.findByNameContainingIgnoreCase(nameFilter.trim(), resolved);
        return PagedResponse.of(page, jobMapper::toCompany);
    }

    public CompanyDetailResponse findById(Long id) {
        var company = companyRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Company", id));
        return new CompanyDetailResponse(
                company.getId(),
                company.getName(),
                company.getIndustry(),
                company.getWebsite(),
                companyRepository.countJobs(id));
    }
}
