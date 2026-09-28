package com.jmip.service.saved;

import com.jmip.common.exception.InvalidRequestException;
import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.dto.saved.SavedJobResponse;
import com.jmip.entity.ApplicationStatus;
import com.jmip.entity.SavedJob;
import com.jmip.mapper.JobMapper;
import com.jmip.repository.JobRepository;
import com.jmip.repository.SavedJobRepository;
import com.jmip.service.auth.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Saved jobs and application tracking for the signed-in account (V7.2). Every operation is
 * scoped to the account taken from the session; another account's record answers exactly
 * like a missing one (404), so its status and notes are never revealed.
 */
@Service
public class SavedJobService {

    /** Far beyond real use; bounds what one account can store. */
    static final int MAX_SAVED_PER_ACCOUNT = 500;

    private static final Logger log = LoggerFactory.getLogger(SavedJobService.class);

    private final SavedJobRepository repository;
    private final JobRepository jobRepository;
    private final JobMapper jobMapper;
    private final CurrentUser currentUser;
    private final Clock clock;

    public SavedJobService(SavedJobRepository repository, JobRepository jobRepository, JobMapper jobMapper,
                           CurrentUser currentUser, Clock clock) {
        this.repository = repository;
        this.jobRepository = jobRepository;
        this.jobMapper = jobMapper;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /** The saved record, and whether this call created it (false: it was already saved). */
    public record SaveResult(SavedJobResponse savedJob, boolean created) {
    }

    /** Saves a job; saving one that is already saved returns the existing record unchanged. */
    @Transactional
    public SaveResult save(Long jobId) {
        UUID ownerId = currentUser.requireId();
        var existing = repository.findByUserIdAndJobId(ownerId, jobId);
        if (existing.isPresent()) {
            return new SaveResult(toResponse(existing.get()), false);
        }
        if (!jobRepository.existsById(jobId)) {
            throw ResourceNotFoundException.of("Job", jobId);
        }
        if (repository.countByUserId(ownerId) >= MAX_SAVED_PER_ACCOUNT) {
            throw new InvalidRequestException(
                    "You can save at most " + MAX_SAVED_PER_ACCOUNT + " jobs; remove one to save another");
        }
        boolean created = repository.insertIfAbsent(UUID.randomUUID(), ownerId, jobId, now()) == 1;
        SavedJob saved = repository.findByUserIdAndJobId(ownerId, jobId)
                .orElseThrow(() -> ResourceNotFoundException.of("Job", jobId));
        if (created) {
            log.info("Job {} saved as {}", jobId, saved.getId());
        }
        return new SaveResult(toResponse(saved), created);
    }

    /** Removes the job from the saved list. Nothing to remove is not an error. */
    @Transactional
    public void unsave(Long jobId) {
        repository.deleteByUserIdAndJobId(currentUser.requireId(), jobId);
    }

    @Transactional(readOnly = true)
    public List<SavedJobResponse> list(ApplicationStatus status) {
        UUID ownerId = currentUser.requireId();
        List<SavedJob> rows = status == null
                ? repository.findByUserIdOrderByUpdatedAtDesc(ownerId)
                : repository.findByUserIdAndStatusOrderByUpdatedAtDesc(ownerId, status);
        return rows.stream().map(this::toResponse).toList();
    }

    @Transactional
    public SavedJobResponse changeStatus(UUID id, ApplicationStatus status) {
        SavedJob saved = requireOwn(id);
        saved.changeStatus(status, now());
        return toResponse(saved);
    }

    @Transactional
    public SavedJobResponse changeNotes(UUID id, String notes) {
        SavedJob saved = requireOwn(id);
        saved.changeNotes(notes, now());
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        repository.delete(requireOwn(id));
    }

    private SavedJob requireOwn(UUID id) {
        return repository.findByIdAndUserId(id, currentUser.requireId())
                .orElseThrow(() -> ResourceNotFoundException.of("Saved job", id));
    }

    private SavedJobResponse toResponse(SavedJob saved) {
        // Skills are left out: the list shows the posting's identity, and the details page has the rest.
        return new SavedJobResponse(saved.getId(), jobMapper.toSummary(saved.getJob(), List.of()), saved.getStatus(),
                saved.getNotes(), saved.getSavedAt(), saved.getAppliedAt(), saved.getUpdatedAt());
    }

    private OffsetDateTime now() {
        // PostgreSQL keeps microseconds; truncating here means a response shows exactly what
        // was stored, not a finer value that later reads would not match.
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
