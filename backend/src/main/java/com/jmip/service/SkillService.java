package com.jmip.service;

import com.jmip.dto.PagedResponse;
import com.jmip.dto.SkillResponse;
import com.jmip.dto.analytics.SkillDemandResponse;
import com.jmip.mapper.JobMapper;
import com.jmip.repository.JobRepository;
import com.jmip.repository.SkillRepository;
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

    /** The most in-demand skills, ranked by how many postings mention them. */
    public List<SkillDemandResponse> top(int limit) {
        long totalJobs = jobRepository.count();
        List<SkillDemandRow> rows = skillRepository.findTopSkills(PageRequest.of(0, limit));
        log.debug("Top {} skills requested, {} returned", limit, rows.size());
        return rows.stream().map(row -> toDemand(row, totalJobs)).toList();
    }

    static SkillDemandResponse toDemand(SkillDemandRow row, long totalJobs) {
        return new SkillDemandResponse(
                row.skillId(),
                row.name(),
                row.category(),
                row.jobCount(),
                percentageOf(row.jobCount(), totalJobs));
    }

    /**
     * Share of all postings, to one decimal place. Skills overlap, so these do not sum
     * to 100.
     */
    static double percentageOf(long jobCount, long totalJobs) {
        if (totalJobs == 0) {
            return 0.0;
        }
        return Math.round(jobCount * 1000.0 / totalJobs) / 10.0;
    }
}
