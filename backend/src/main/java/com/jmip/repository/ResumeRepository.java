package com.jmip.repository;

import com.jmip.entity.Resume;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResumeRepository extends JpaRepository<Resume, UUID> {

    /**
     * Skills come with the resume in one query. Every caller needs them, and reading them
     * lazily afterwards would be a second round trip for no benefit.
     */
    @EntityGraph(attributePaths = "skills")
    @Query("select r from Resume r where r.id = :id")
    Optional<Resume> findWithSkillsById(@Param("id") UUID id);

    /** Oldest first, a batch at a time, for the retention sweep. */
    List<Resume> findByUploadedAtBeforeOrderByUploadedAtAsc(OffsetDateTime cutoff, Pageable page);

    // ------------------------------------------------------------------ V7.3 versions

    /** The account's resumes, newest first, with their skills in the same query. */
    @EntityGraph(attributePaths = "skills")
    List<Resume> findByUserIdOrderByUploadedAtDesc(UUID userId);

    long countByUserId(UUID userId);

    boolean existsByUserIdAndDefaultResumeTrue(UUID userId);

    Optional<Resume> findFirstByUserIdOrderByUploadedAtDesc(UUID userId);

    /** Clears the account's default, so another resume can take it (one default per account). */
    @Modifying(flushAutomatically = true)
    @Query("update Resume r set r.defaultResume = false where r.userId = :userId and r.defaultResume = true")
    int clearDefault(@Param("userId") UUID userId);
}
