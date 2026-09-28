package com.jmip.service.alert;

import com.jmip.common.exception.InvalidRequestException;
import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.dto.alert.JobAlertRequest;
import com.jmip.dto.alert.JobAlertResponse;
import com.jmip.entity.JobAlert;
import com.jmip.repository.JobAlertRepository;
import com.jmip.service.auth.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Job alerts of the signed-in account (V7.1). Every operation is scoped to the account taken
 * from the session; another account's alert answers exactly like a missing one (404).
 */
@Service
public class JobAlertService {

    /** Enough for real use, and a bound on what one account can make a future run do. */
    static final int MAX_ALERTS_PER_ACCOUNT = 25;

    private static final Logger log = LoggerFactory.getLogger(JobAlertService.class);

    private final JobAlertRepository repository;
    private final CurrentUser currentUser;
    private final Clock clock;

    public JobAlertService(JobAlertRepository repository, CurrentUser currentUser, Clock clock) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Transactional
    public JobAlertResponse create(JobAlertRequest request) {
        UUID ownerId = currentUser.requireId();
        if (repository.countByUserId(ownerId) >= MAX_ALERTS_PER_ACCOUNT) {
            throw new InvalidRequestException(
                    "You can have at most " + MAX_ALERTS_PER_ACCOUNT + " job alerts; delete one to add another");
        }
        JobAlert alert = repository.save(new JobAlert(UUID.randomUUID(), ownerId, request.toCriteria(), now()));
        log.info("Job alert {} created", alert.getId());
        return JobAlertResponse.of(alert);
    }

    @Transactional(readOnly = true)
    public List<JobAlertResponse> list() {
        return repository.findByUserIdOrderByCreatedAtDesc(currentUser.requireId()).stream()
                .map(JobAlertResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public JobAlertResponse get(UUID id) {
        return JobAlertResponse.of(requireOwn(id));
    }

    @Transactional
    public JobAlertResponse update(UUID id, JobAlertRequest request) {
        JobAlert alert = requireOwn(id);
        alert.update(request.toCriteria(), now());
        return JobAlertResponse.of(alert);
    }

    @Transactional
    public JobAlertResponse setActive(UUID id, boolean active) {
        JobAlert alert = requireOwn(id);
        alert.setActive(active, now());
        return JobAlertResponse.of(alert);
    }

    @Transactional
    public void delete(UUID id) {
        repository.delete(requireOwn(id));
        log.info("Job alert {} deleted", id);
    }

    private JobAlert requireOwn(UUID id) {
        return repository.findByIdAndUserId(id, currentUser.requireId())
                .orElseThrow(() -> ResourceNotFoundException.of("Job alert", id));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
