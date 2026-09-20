package com.jmip.repository;

import com.jmip.entity.Skill;
import com.jmip.repository.projection.SkillDemandRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SkillRepository extends JpaRepository<Skill, Long> {

    Page<Skill> findByNameContainingIgnoreCase(String name, Pageable pageable);

    @Query(value = """
            select new com.jmip.repository.projection.SkillDemandRow(
                s.id, s.name, s.category, count(j.id))
            from Job j join j.skills s
            group by s.id, s.name, s.category
            order by count(j.id) desc, s.name asc
            """,
            countQuery = "select count(distinct s.id) from Job j join j.skills s")
    Page<SkillDemandRow> findSkillDemand(Pageable pageable);

    /** The same ranking, capped, for the "top skills" shortcut. */
    @Query("""
            select new com.jmip.repository.projection.SkillDemandRow(
                s.id, s.name, s.category, count(j.id))
            from Job j join j.skills s
            group by s.id, s.name, s.category
            order by count(j.id) desc, s.name asc
            """)
    List<SkillDemandRow> findTopSkills(Pageable limit);

    /**
     * Exact match on name, ignoring case.
     *
     * <p>Used by the V5 assistant to turn a skill named in a question into the skill row
     * that actually exists. Exact rather than fuzzy on purpose: "Java" must not quietly
     * resolve to "JavaScript", and a near miss should ask the user rather than guess.
     */
    Optional<Skill> findFirstByNameIgnoreCase(String name);
}
