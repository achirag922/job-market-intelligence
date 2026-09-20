package com.jmip.repository;

import com.jmip.entity.Job;
import com.jmip.repository.projection.JobSkillRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<Job, Long>, JpaSpecificationExecutor<Job> {

    /**
     * Company and location are fetched with the page rather than lazily per row. Both are
     * to-one associations, so this stays a single query and paging is still done by the
     * database.
     */
    @Override
    @EntityGraph(attributePaths = {"company", "location"})
    Page<Job> findAll(org.springframework.data.jpa.domain.Specification<Job> specification, Pageable pageable);

    @EntityGraph(attributePaths = {"company", "location", "skills"})
    @Query("select j from Job j where j.id = :id")
    Optional<Job> findDetailById(@Param("id") Long id);

    /**
     * Skills for a whole page of jobs in one query. Fetching them as part of the page
     * query instead would force Hibernate to page in memory, because the collection
     * multiplies the rows.
     */
    @Query("""
            select new com.jmip.repository.projection.JobSkillRow(j.id, s)
            from Job j join j.skills s
            where j.id in :jobIds
            order by s.name asc
            """)
    List<JobSkillRow> findSkillsForJobs(@Param("jobIds") Collection<Long> jobIds);
}
