package com.jmip.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * An uploaded resume and what was extracted from it.
 *
 * <p>Unlike the V1 entities, which the ETL writes and the API only reads, this one is
 * written here. It therefore has behaviour for its own state transitions rather than open
 * setters, so a row cannot end up marked FAILED with no reason or COMPLETED with a stale
 * error — invariants the schema also enforces.
 */
@Entity
@Table(name = "resumes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Resume {

    @Id
    private UUID id;

    /** The account that uploaded it; null only for resumes from before accounts existed. */
    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "original_file_name", nullable = false)
    private String originalFileName;

    @Column(name = "stored_file_name", nullable = false)
    private String storedFileName;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false)
    private ResumeProcessingStatus processingStatus;

    /** Encrypted at rest when a resume encryption key is configured. */
    @Convert(converter = EncryptedTextConverter.class)
    @Column(name = "extracted_text")
    private String extractedText;

    @Column(name = "error_message")
    private String errorMessage;

    /**
     * Set here rather than left to the column default. A database default is only visible
     * after a re-read, so the value returned from the upload call itself would be null —
     * exactly when the caller most wants it.
     */
    @Column(name = "uploaded_at", updatable = false)
    private OffsetDateTime uploadedAt;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "resume_skills",
            joinColumns = @JoinColumn(name = "resume_id"),
            inverseJoinColumns = @JoinColumn(name = "skill_id"))
    private Set<Skill> skills = new LinkedHashSet<>();

    public Resume(UUID id, String originalFileName, String storedFileName,
                  String contentType, long fileSizeBytes, OffsetDateTime uploadedAt) {
        this.id = id;
        this.originalFileName = originalFileName;
        this.storedFileName = storedFileName;
        this.contentType = contentType;
        this.fileSizeBytes = fileSizeBytes;
        this.uploadedAt = uploadedAt;
        this.processingStatus = ResumeProcessingStatus.UPLOADED;
    }

    /** A resume owned by {@code userId}, the account that is uploading it. */
    public Resume(UUID id, UUID userId, String originalFileName, String storedFileName,
                  String contentType, long fileSizeBytes, OffsetDateTime uploadedAt) {
        this(id, originalFileName, storedFileName, contentType, fileSizeBytes, uploadedAt);
        this.userId = userId;
    }

    /** False for every account when the resume has no owner. */
    public boolean isOwnedBy(UUID accountId) {
        return userId != null && userId.equals(accountId);
    }

    public void markProcessing() {
        this.processingStatus = ResumeProcessingStatus.PROCESSING;
        this.errorMessage = null;
    }

    /**
     * @param skills the skills found, replacing anything from an earlier attempt so a
     *               re-run cannot accumulate stale results
     */
    public void markCompleted(String extractedText, Set<Skill> skills, OffsetDateTime processedAt) {
        this.processingStatus = ResumeProcessingStatus.COMPLETED;
        this.extractedText = extractedText;
        this.skills.clear();
        this.skills.addAll(skills);
        this.errorMessage = null;
        this.processedAt = processedAt;
    }

    public void markFailed(String errorMessage, OffsetDateTime processedAt) {
        this.processingStatus = ResumeProcessingStatus.FAILED;
        // The schema requires a reason on failure; never let it be blank.
        this.errorMessage = errorMessage == null || errorMessage.isBlank()
                ? "Resume processing failed" : errorMessage;
        this.processedAt = processedAt;
    }

    public boolean isCompleted() {
        return processingStatus == ResumeProcessingStatus.COMPLETED;
    }
}
