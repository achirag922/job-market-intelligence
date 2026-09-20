package com.jmip.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A job posting.
 *
 * <p>Associations are lazy throughout. Queries that need a company or location join fetch
 * it explicitly, and skills are loaded for a whole page in one query rather than per row,
 * because the alternative on a 20-row page is 20 extra round trips.
 */
@Entity
@Table(name = "jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    /** Null for remote postings, which genuinely have no location. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id")
    private Location location;

    private String description;

    @Column(name = "employment_type")
    private String employmentType;

    /** SMALLINT in the schema, so Short rather than Integer, or validation fails. */
    @Column(name = "experience_min")
    private Short experienceMin;

    @Column(name = "experience_max")
    private Short experienceMax;

    @Column(name = "salary_min")
    private BigDecimal salaryMin;

    @Column(name = "salary_max")
    private BigDecimal salaryMax;

    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(name = "posted_date")
    private LocalDate postedDate;

    @Column(nullable = false)
    private String source;

    @Column(name = "source_url")
    private String sourceUrl;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_fingerprint", nullable = false)
    private String contentFingerprint;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Maintained by a database trigger, so never written from here. */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "job_skills",
            joinColumns = @JoinColumn(name = "job_id"),
            inverseJoinColumns = @JoinColumn(name = "skill_id"))
    private Set<Skill> skills = new LinkedHashSet<>();
}
