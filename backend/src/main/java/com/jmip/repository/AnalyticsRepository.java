package com.jmip.repository;

import com.jmip.entity.Job;
import com.jmip.repository.projection.ExperienceCountRow;
import com.jmip.repository.projection.SkillDemandRow;
import com.jmip.repository.projection.TitleCountRow;
import com.jmip.repository.projection.TitleSkillCountRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * Aggregate queries behind the analytics endpoints.
 *
 * <p>They live together, away from the CRUD repositories, for one specific reason: the
 * filtered skill query and the query that counts its denominator must apply exactly the
 * same predicate. Percentages computed against a differently filtered total would be
 * quietly, plausibly wrong. Keeping the pair adjacent makes any divergence obvious, and
 * a test asserts they agree.
 *
 * <p>Every filter uses the {@code :param is null or ...} idiom so one query serves both
 * the filtered and unfiltered cases.
 *
 * <p>{@code left join} on location throughout: an inner join would silently drop remote
 * postings, which have no location, even when no location filter was asked for.
 */
public interface AnalyticsRepository extends Repository<Job, Long> {

    @Query(value = """
            select new com.jmip.repository.projection.SkillDemandRow(s.id, s.name, s.category, count(j.id))
            from Job j left join j.location l join j.skills s
            where (:location is null
                   or lower(l.city) like :location
                   or lower(l.state) like :location
                   or lower(l.country) like :location)
              and (cast(:fromDate as LocalDate) is null or j.postedDate >= :fromDate)
              and (cast(:toDate as LocalDate) is null or j.postedDate <= :toDate)
              and (:titlePattern is null or lower(j.title) like :titlePattern)
            group by s.id, s.name, s.category
            order by count(j.id) desc, s.name asc
            """,
            countQuery = """
            select count(distinct s.id)
            from Job j left join j.location l join j.skills s
            where (:location is null
                   or lower(l.city) like :location
                   or lower(l.state) like :location
                   or lower(l.country) like :location)
              and (cast(:fromDate as LocalDate) is null or j.postedDate >= :fromDate)
              and (cast(:toDate as LocalDate) is null or j.postedDate <= :toDate)
              and (:titlePattern is null or lower(j.title) like :titlePattern)
            """)
    Page<SkillDemandRow> findSkillDemand(@Param("location") String location,
                                         @Param("fromDate") LocalDate fromDate,
                                         @Param("toDate") LocalDate toDate,
                                         @Param("titlePattern") String titlePattern,
                                         Pageable pageable);

    /**
     * The denominator for the percentages above: how many postings the same filters
     * match. Counts postings, not postings-with-skills, so a job with no extracted skills
     * still counts towards the total it is part of.
     */
    @Query("""
            select count(j.id)
            from Job j left join j.location l
            where (:location is null
                   or lower(l.city) like :location
                   or lower(l.state) like :location
                   or lower(l.country) like :location)
              and (cast(:fromDate as LocalDate) is null or j.postedDate >= :fromDate)
              and (cast(:toDate as LocalDate) is null or j.postedDate <= :toDate)
              and (:titlePattern is null or lower(j.title) like :titlePattern)
            """)
    long countJobsInScope(@Param("location") String location,
                          @Param("fromDate") LocalDate fromDate,
                          @Param("toDate") LocalDate toDate,
                          @Param("titlePattern") String titlePattern);

    /**
     * Postings grouped by the exact number of years they ask for. Folded into bands in
     * Java, where the boundaries are explicit and testable rather than buried in SQL.
     */
    @Query("select new com.jmip.repository.projection.ExperienceCountRow(j.experienceMin, count(j.id)) "
            + "from Job j group by j.experienceMin")
    List<ExperienceCountRow> countByExperienceMin();

    @Query("""
            select new com.jmip.repository.projection.SkillDemandRow(s.id, s.name, s.category, count(j.id))
            from Job j join j.skills s
            where j.company.id = :companyId
            group by s.id, s.name, s.category
            order by count(j.id) desc, s.name asc
            """)
    List<SkillDemandRow> findSkillsForCompany(@Param("companyId") Long companyId, Pageable limit);

    @Query("""
            select new com.jmip.repository.projection.SkillDemandRow(s.id, s.name, s.category, count(j.id))
            from Job j join j.skills s
            where j.location.id = :locationId
            group by s.id, s.name, s.category
            order by count(j.id) desc, s.name asc
            """)
    List<SkillDemandRow> findSkillsForLocation(@Param("locationId") Long locationId, Pageable limit);

    @Query("""
            select new com.jmip.repository.projection.TitleCountRow(j.title, count(j.id))
            from Job j
            where j.location.id = :locationId
            group by j.title
            """)
    List<TitleCountRow> countTitlesForLocation(@Param("locationId") Long locationId);

    /** Distinct titles are far fewer than postings, so the whole set is folded in Java. */
    @Query("select new com.jmip.repository.projection.TitleCountRow(j.title, count(j.id)) "
            + "from Job j group by j.title")
    List<TitleCountRow> countByTitle();

    @Query("""
            select new com.jmip.repository.projection.TitleSkillCountRow(
                j.title, s.id, s.name, s.category, count(j.id))
            from Job j join j.skills s
            group by j.title, s.id, s.name, s.category
            """)
    List<TitleSkillCountRow> countSkillsByTitle();

    @Query("select count(j.id) from Job j where j.company.id = :companyId")
    long countJobsForCompany(@Param("companyId") Long companyId);

    @Query("select count(j.id) from Job j where j.location.id = :locationId")
    long countJobsForLocation(@Param("locationId") Long locationId);
}
