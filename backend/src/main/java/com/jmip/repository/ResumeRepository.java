package com.jmip.repository;

import com.jmip.entity.Resume;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
