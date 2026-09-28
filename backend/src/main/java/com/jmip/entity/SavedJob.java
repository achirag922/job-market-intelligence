package com.jmip.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A job an account has saved, with its application status and private notes (V7.2). */
@Entity
@Table(name = "saved_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedJob {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false, updatable = false)
    private Job job;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApplicationStatus status;

    private String notes;

    @Column(name = "saved_at", nullable = false, updatable = false)
    private OffsetDateTime savedAt;

    @Column(name = "applied_at")
    private OffsetDateTime appliedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public SavedJob(UUID id, UUID userId, Job job, OffsetDateTime now) {
        this.id = id;
        this.userId = userId;
        this.job = job;
        this.status = ApplicationStatus.SAVED;
        this.savedAt = now;
        this.updatedAt = now;
    }

    /**
     * The application date is when it first left SAVED; later moves keep it. Moving back to
     * SAVED means "not applied after all" and clears it.
     */
    public void changeStatus(ApplicationStatus next, OffsetDateTime now) {
        if (next == ApplicationStatus.SAVED) {
            appliedAt = null;
        } else if (appliedAt == null) {
            appliedAt = now;
        }
        status = next;
        updatedAt = now;
    }

    public void changeNotes(String notes, OffsetDateTime now) {
        this.notes = notes;
        this.updatedAt = now;
    }
}
