package com.jmip.entity;

import com.jmip.dto.JobSearchCriteria;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A saved job search that its owner wants to be told about (V7.1).
 *
 * <p>The criteria are the job search's own filters, so {@link #toSearchCriteria()} can run
 * the alert through the existing search unchanged.
 */
@Entity
@Table(name = "job_alerts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobAlert {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false)
    private String name;

    private String keywords;

    private String category;

    private String location;

    private String experience;

    private String skill;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertFrequency frequency;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public JobAlert(UUID id, UUID userId, Criteria criteria, OffsetDateTime now) {
        this.id = id;
        this.userId = userId;
        this.active = true;
        this.createdAt = now;
        apply(criteria, now);
    }

    public void update(Criteria criteria, OffsetDateTime now) {
        apply(criteria, now);
    }

    public void setActive(boolean active, OffsetDateTime now) {
        this.active = active;
        this.updatedAt = now;
    }

    public boolean isOwnedBy(UUID accountId) {
        return userId.equals(accountId);
    }

    /** The alert as a job search, for running it against current postings. */
    public JobSearchCriteria toSearchCriteria() {
        return new JobSearchCriteria(keywords, null, location, null, skill, null, category,
                experience, null, null, null, null);
    }

    private void apply(Criteria criteria, OffsetDateTime now) {
        this.name = criteria.name();
        this.keywords = criteria.keywords();
        this.category = criteria.category();
        this.location = criteria.location();
        this.experience = criteria.experience();
        this.skill = criteria.skill();
        this.frequency = criteria.frequency();
        this.updatedAt = now;
    }

    /** Validated, normalised values for an alert. */
    public record Criteria(String name, String keywords, String category, String location, String experience,
                           String skill, AlertFrequency frequency) {
    }
}
