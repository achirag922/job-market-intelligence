package com.jmip.service.resume;

import com.jmip.common.exception.InvalidRequestException;
import com.jmip.common.exception.ResourceNotFoundException;
import com.jmip.dto.SkillResponse;
import com.jmip.dto.resume.ResumeResponse;
import com.jmip.dto.resume.ResumeSkillsResponse;
import com.jmip.entity.Resume;
import com.jmip.entity.Skill;
import com.jmip.mapper.JobMapper;
import com.jmip.service.auth.CurrentUser;
import com.jmip.repository.ResumeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Upload, extraction and retrieval of resumes.
 *
 * <p>Extraction runs inside the upload request rather than on a background thread. For a
 * few megabytes of PDF it takes milliseconds, and doing it inline means the caller gets a
 * final answer in one round trip instead of polling. The status column still models the
 * full lifecycle, so moving extraction onto a queue later changes this class and nothing
 * the API exposes.
 */
@Service
public class ResumeService {

    /** V7.3: enough versions for real use, and a bound on what one account can store. */
    static final int MAX_RESUMES_PER_ACCOUNT = 20;

    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);

    private final ResumeRepository resumeRepository;
    private final ResumeStorageService storageService;
    private final ResumeTextExtractor textExtractor;
    private final ResumeTextNormalizer textNormalizer;
    private final ResumeSkillMatcher skillMatcher;
    private final JobMapper jobMapper;
    private final Clock clock;
    private final CurrentUser currentUser;

    public ResumeService(ResumeRepository resumeRepository,
                         ResumeStorageService storageService,
                         ResumeTextExtractor textExtractor,
                         ResumeTextNormalizer textNormalizer,
                         ResumeSkillMatcher skillMatcher,
                         JobMapper jobMapper,
                         Clock clock,
                         CurrentUser currentUser) {
        this.resumeRepository = resumeRepository;
        this.storageService = storageService;
        this.textExtractor = textExtractor;
        this.textNormalizer = textNormalizer;
        this.skillMatcher = skillMatcher;
        this.jobMapper = jobMapper;
        this.clock = clock;
        this.currentUser = currentUser;
    }

    /**
     * Validates, stores and processes an upload.
     *
     * <p>A document that cannot be read is not an error for the caller to retry: the
     * upload succeeded, and the resume is recorded as FAILED with the reason. That keeps
     * the failure visible and explicable instead of vanishing with a 500.
     */
    @Transactional
    public ResumeResponse upload(MultipartFile file) {
        // The owner comes from the session, before anything is stored.
        UUID ownerId = currentUser.requireId();
        if (resumeRepository.countByUserId(ownerId) >= MAX_RESUMES_PER_ACCOUNT) {
            throw new InvalidRequestException("You can keep at most " + MAX_RESUMES_PER_ACCOUNT
                    + " resumes; delete one to upload another");
        }
        storageService.validate(file);

        UUID resumeId = UUID.randomUUID();
        byte[] content = readBytes(file);
        String storedFileName = storageService.store(resumeId, content);

        Resume resume = new Resume(
                resumeId,
                ownerId,
                sanitizeDisplayName(file.getOriginalFilename()),
                storedFileName,
                file.getContentType(),
                content.length,
                OffsetDateTime.now(clock));
        resume.markProcessing();
        // V7.3: an account's first resume is its default until the owner picks another.
        if (!resumeRepository.existsByUserIdAndDefaultResumeTrue(ownerId)) {
            resume.setDefault(true, resume.getUploadedAt());
        }

        try {
            String text = textNormalizer.normalize(textExtractor.extractText(content));
            Set<Skill> skills = skillMatcher.match(text);
            resume.markCompleted(text, skills, OffsetDateTime.now(clock));
            log.info("Resume {} processed: {} skills extracted", resumeId, skills.size());
        } catch (ResumeTextExtractionException e) {
            // Expected for scanned or corrupt documents. Keep the file so the failure can
            // be investigated, and record why.
            log.warn("Resume {} could not be processed: {}", resumeId, e.getMessage());
            resume.markFailed(e.getMessage(), OffsetDateTime.now(clock));
        }

        return toResponse(resumeRepository.save(resume));
    }

    /**
     * Deletes the caller's resume and everything derived from it: the row, its extracted
     * text, its skill links (cascaded by the schema), and the stored file. Matches,
     * recommendations and career insights are computed on request and never stored, so
     * nothing else remains.
     *
     * @throws ResourceNotFoundException for someone else's, a missing or an already deleted
     *                                   resume — the same answer in every case
     */
    @Transactional
    public void delete(UUID id) {
        Resume resume = requireResume(id);
        remove(resume);
        log.info("Resume {} deleted by its owner", id);
    }

    /**
     * The retention sweep: deletes up to {@code batchSize} resumes uploaded before
     * {@code cutoff}, oldest first.
     *
     * @return how many were deleted
     */
    @Transactional
    public int deleteUploadedBefore(OffsetDateTime cutoff, int batchSize) {
        List<Resume> expired = resumeRepository.findByUploadedAtBeforeOrderByUploadedAtAsc(cutoff,
                org.springframework.data.domain.PageRequest.of(0, batchSize));
        expired.forEach(this::remove);
        return expired.size();
    }

    /** Row now; file once the delete has committed, so a rollback never loses a file it still needs. */
    private void remove(Resume resume) {
        String storedFileName = resume.getStoredFileName();
        resumeRepository.delete(resume);
        resumeRepository.flush();
        // V7.3: deleting the default hands it to the newest remaining resume, if any.
        if (resume.isDefaultResume() && resume.getUserId() != null) {
            resumeRepository.findFirstByUserIdOrderByUploadedAtDesc(resume.getUserId())
                    .ifPresent(next -> next.setDefault(true, OffsetDateTime.now(clock)));
        }
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            storageService.deleteQuietly(storedFileName);
                        }
                    });
        } else {
            storageService.deleteQuietly(storedFileName);
        }
    }

    // ------------------------------------------------------------------ V7.3 versions

    /** The caller's resumes, newest first. */
    @Transactional(readOnly = true)
    public List<ResumeResponse> list() {
        return resumeRepository.findByUserIdOrderByUploadedAtDesc(currentUser.requireId()).stream()
                .map(this::toResponse)
                .toList();
    }

    /** Renames or relabels one of the caller's resumes. */
    @Transactional
    public ResumeResponse describe(UUID id, String title, String versionLabel) {
        Resume resume = requireResume(id);
        resume.describe(title, versionLabel, OffsetDateTime.now(clock));
        return toResponse(resume);
    }

    /** Makes one of the caller's resumes the default; the previous default stops being one. */
    @Transactional
    public ResumeResponse makeDefault(UUID id) {
        Resume resume = requireResume(id);
        resumeRepository.clearDefault(resume.getUserId());
        resume.setDefault(true, OffsetDateTime.now(clock));
        return toResponse(resume);
    }

    @Transactional(readOnly = true)
    public ResumeResponse findById(UUID id) {
        return toResponse(requireResume(id));
    }

    @Transactional(readOnly = true)
    public ResumeSkillsResponse findSkills(UUID id) {
        Resume resume = requireResume(id);
        requireProcessed(resume);
        List<SkillResponse> skills = sortedSkills(resume.getSkills());
        return new ResumeSkillsResponse(resume.getId(), skills.size(), skills);
    }

    /**
     * Loads a resume that has finished processing, for callers that need its skills.
     *
     * @throws ResourceNotFoundException if no such resume exists
     * @throws ResumeNotReadyException   if it is still processing or failed
     */
    @Transactional(readOnly = true)
    public Resume requireCompletedResume(UUID id) {
        Resume resume = requireResume(id);
        requireProcessed(resume);
        return resume;
    }

    /**
     * The one way any feature reaches a resume — fetch, skills, match, recommendations,
     * career insights, the assistant — so ownership is enforced here, once.
     *
     * <p>Someone else's resume gets exactly the same 404 as one that does not exist: a
     * different answer would confirm that the id is real.
     */
    private Resume requireResume(UUID id) {
        UUID ownerId = currentUser.requireId();
        return resumeRepository.findWithSkillsById(id)
                .filter(resume -> resume.isOwnedBy(ownerId))
                .orElseThrow(() -> ResourceNotFoundException.of("Resume", id));
    }

    private static void requireProcessed(Resume resume) {
        if (resume.isCompleted()) {
            return;
        }
        throw switch (resume.getProcessingStatus()) {
            case FAILED -> new ResumeNotReadyException(
                    "Resume processing failed: " + resume.getErrorMessage());
            default -> new ResumeNotReadyException(
                    "Resume is still being processed, its status is " + resume.getProcessingStatus());
        };
    }

    private ResumeResponse toResponse(Resume resume) {
        return new ResumeResponse(
                resume.getId(),
                resume.getOriginalFileName(),
                resume.getFileSizeBytes(),
                resume.getProcessingStatus().name(),
                sortedSkills(resume.getSkills()),
                resume.getErrorMessage(),
                resume.getUploadedAt(),
                resume.getProcessedAt(),
                resume.getTitle(),
                resume.getVersionLabel(),
                resume.isDefaultResume(),
                resume.getUpdatedAt());
    }

    private List<SkillResponse> sortedSkills(Set<Skill> skills) {
        return skills.stream()
                .map(jobMapper::toSkill)
                .sorted(Comparator.comparing(SkillResponse::name))
                .toList();
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded file", e);
        }
    }

    /**
     * The uploaded name is only ever displayed, never used as a path, but it still goes
     * into the database and back out to a browser, so any directory separators are
     * stripped and the length is bounded to the column.
     */
    private static String sanitizeDisplayName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "resume.pdf";
        }
        // Path separators and control characters (NUL, CR, LF, ...) are replaced, so the
        // display name can neither look like a path nor forge lines wherever it is shown.
        String name = originalFilename.replaceAll("[\\\\/\\p{Cntrl}]", "_").strip();
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }
}
