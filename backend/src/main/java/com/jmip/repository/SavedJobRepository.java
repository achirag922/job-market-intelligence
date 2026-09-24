package com.jmip.repository;

import com.jmip.entity.ApplicationStatus;
import com.jmip.entity.SavedJob;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every finder is scoped to the owner: another account's row is indistinguishable from a missing one. */
public interface SavedJobRepository extends JpaRepository<SavedJob, UUID> {

    /** The job, its company and location in the same query, for the list view. */
    @EntityGraph(attributePaths = {"job", "job.company", "job.location"})
    List<SavedJob> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    @EntityGraph(attributePaths = {"job", "job.company", "job.location"})
    List<SavedJob> findByUserIdAndStatusOrderByUpdatedAtDesc(UUID userId, ApplicationStatus status);

    @EntityGraph(attributePaths = {"job", "job.company", "job.location"})
    Optional<SavedJob> findByIdAndUserId(UUID id, UUID userId);

    @EntityGraph(attributePaths = {"job", "job.company", "job.location"})
    Optional<SavedJob> findByUserIdAndJobId(UUID userId, Long jobId);

    long countByUserId(UUID userId);

    /**
     * Saves the job for the account unless it already is: two quick clicks, or two tabs, end
     * with one row and no error. Returns 1 when a row was added, 0 when it was already there.
     */
    @Modifying
    @Query(value = """
            INSERT INTO saved_jobs (id, user_id, job_id, status, saved_at, updated_at)
            VALUES (:id, :userId, :jobId, 'SAVED', :now, :now)
            ON CONFLICT (user_id, job_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("userId") UUID userId, @Param("jobId") Long jobId,
                       @Param("now") OffsetDateTime now);

    @Modifying
    @Query("delete from SavedJob s where s.userId = :userId and s.job.id = :jobId")
    int deleteByUserIdAndJobId(@Param("userId") UUID userId, @Param("jobId") Long jobId);
}
