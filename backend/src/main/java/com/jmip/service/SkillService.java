package com.jmip.service;

import com.jmip.dto.PagedResponse;
import com.jmip.dto.SkillResponse;
import com.jmip.dto.analytics.SkillDemandResponse;
import com.jmip.mapper.JobMapper;
import com.jmip.repository.JobRepository;
import com.jmip.repository.SkillRepository;
import com.jmip.service.analytics.Metrics;
import com.jmip.repository.projection.SkillDemandRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    private static final SortWhitelist SORTABLE = SortWhitelist.of(Map.of(
            "name", "name",
            "category", "category",
            "createdAt", "createdAt"));

    private final SkillRepository skillRepository;
    private final JobRepository jobRepository;
    private final JobMapper jobMapper;

    public SkillService(SkillRepository skillRepository, JobRepository jobRepository, JobMapper jobMapper) {
        this.skillRepository = skillRepository;
        this.jobRepository = jobRepository;
        this.jobMapper = jobMapper;
    }

    public PagedResponse<SkillResponse> list(String nameFilter, Pageable pageable) {
        Pageable resolved = SORTABLE.apply(pageable);
        var page = nameFilter == null || nameFilter.isBlank()
                ? skillRepository.findAll(resolved)
                : skillRepository.findByNameContainingIgnoreCase(nameFilter.trim(), resolved);
        return PagedResponse.of(page, jobMapper::toSkill);
    }

    /**
     * The most in-demand skills, ranked by how many postings mention them.
     *
     * <p>Percentages here are against every posting. The filtered equivalent lives on
     * {@code /api/analytics/skills}, which also reports the total it measured against.
     */
    public List<SkillDemandResponse> top(int limit) {
        long totalJobs = jobRepository.count();
        List<SkillDemandRow> rows = skillRepository.findTopSkills(PageRequest.of(0, limit));
        log.debug("Top {} skills requested, {} returned", limit, rows.size());

        List<SkillDemandResponse> result = new java.util.ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            SkillDemandRow row = rows.get(index);
            result.add(new SkillDemandResponse(
                    row.skillId(),
                    row.name(),
                    row.category(),
                    row.jobCount(),
                    Metrics.percentageOf(row.jobCount(), totalJobs),
                    index + 1));
        }
        return result;
    }
}
