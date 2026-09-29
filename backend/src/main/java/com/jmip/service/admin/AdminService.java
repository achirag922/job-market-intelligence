package com.jmip.service.admin;

import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.dto.PagedResponse;
import com.jmip.dto.admin.AdminDtos.DataQuality;
import com.jmip.dto.admin.AdminDtos.Etl;
import com.jmip.dto.admin.AdminDtos.Health;
import com.jmip.dto.admin.AdminDtos.Overview;
import com.jmip.dto.admin.AdminDtos.UserDetail;
import com.jmip.dto.admin.AdminDtos.UserSummary;
import com.jmip.dto.admin.AdminDtos.Users;
import com.jmip.dto.etl.EtlRunResponse;
import com.jmip.dto.etl.JobSourceResponse;
import com.jmip.repository.AdminRepository;
import com.jmip.repository.JobSourceRepository;
import com.jmip.service.EtlRunService;
import com.jmip.service.auth.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V8.9: the admin area's figures, reusing the ETL monitoring service (V6.5), the job-source
 * repository (V8.1), Actuator health (V7.8) and read-only aggregates. Access is decided by the
 * security configuration (ADMIN only) before any of this runs.
 */
@Service
@Transactional(readOnly = true)
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    static final int RECENT_RUNS = 5;
    static final int ACTIVITY_DAYS = 7;
    static final int TOP_REASONS = 5;
    static final int MAX_PAGE_SIZE = 100;
    static final String ACTIVE_DEFINITION = "Accounts that saved, uploaded or changed something in the last 30 days. "
            + "Sign-ins are not recorded, so an account that only browsed does not count.";
    static final String NO_DEACTIVATION = "Accounts cannot be deactivated: the user model has no active flag.";

    private final AdminRepository repository;
    private final JobSourceRepository jobSources;
    private final EtlRunService etlRuns;
    private final ObjectProvider<HealthEndpoint> health;
    private final CurrentUser currentUser;
    private final Clock clock;

    public AdminService(AdminRepository repository, JobSourceRepository jobSources, EtlRunService etlRuns,
                        ObjectProvider<HealthEndpoint> health, CurrentUser currentUser, Clock clock) {
        this.repository = repository;
        this.jobSources = jobSources;
        this.etlRuns = etlRuns;
        this.health = health;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    public Overview overview() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        AdminRepository.UserCounts counts = repository.userCounts(now.minusDays(30));
        Users users = new Users(counts.total(), counts.verified(), counts.admins(), counts.newSince(),
                repository.activeUsers(now.minusDays(30)), ACTIVE_DEFINITION);
        List<EtlRunResponse> recent = etlRuns.runs(null, 0, RECENT_RUNS).content();
        Etl etl = new Etl(recent.isEmpty() ? null : recent.get(0), recent,
                repository.failedRunsSince(LocalDateTime.now(clock).minusDays(30)));
        return new Overview(users, repository.jobs(now.minusDays(7), now.minusDays(30)), etl, jobSources.findAll(),
                health(), repository.activity(ACTIVITY_DAYS, now.minusDays(ACTIVITY_DAYS)));
    }

    public DataQuality dataQuality() {
        List<String> notes = new ArrayList<>();
        notes.add("Totals cover every recorded ingestion run. Rejected records are counted by reason only; their raw "
                + "input is never shown.");
        notes.add("Rejections are not recorded per source, so the per-source figures show loaded and seen-again "
                + "postings and current job status.");
        return new DataQuality(repository.totals(), repository.current(LocalDate.now(clock)),
                repository.topRejectionReasons(TOP_REASONS), repository.sources(), notes);
    }

    public PagedResponse<UserSummary> users(String query, String role, Boolean verified, int page, int size) {
        int limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        AdminRepository.UserPage result = repository.users(blankToNull(query), blankToNull(role), verified, limit,
                (long) page * limit);
        int totalPages = (int) ((result.total() + limit - 1) / limit);
        return new PagedResponse<>(result.users(), page, limit, result.total(), totalPages, page == 0,
                page >= totalPages - 1);
    }

    public UserDetail user(UUID id) {
        return repository.user(id, NO_DEACTIVATION).orElseThrow(() -> ResourceNotFoundException.of("User", id));
    }

    /** Turns a source on or off. The ETL rejects an inactive source's records from its next run. */
    @Transactional
    public JobSourceResponse setSourceActive(long id, boolean active) {
        if (!jobSources.setActive(id, active)) {
            throw ResourceNotFoundException.of("Job source", id);
        }
        // The admin's id, never their email.
        log.info("Job source {} set {} by admin {}", id, active ? "active" : "inactive", currentUser.requireId());
        return jobSources.findById(id).orElseThrow(() -> ResourceNotFoundException.of("Job source", id));
    }

    private Health health() {
        HealthEndpoint endpoint = health.getIfAvailable();
        if (endpoint == null) {
            return new Health("UNKNOWN", Map.of());
        }
        HealthComponent overall = endpoint.health();
        Map<String, String> components = new LinkedHashMap<>();
        if (overall instanceof CompositeHealth composite) {
            composite.getComponents().forEach((name, component) -> components.put(name, component.getStatus().getCode()));
        }
        return new Health(overall.getStatus().getCode(), components);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
