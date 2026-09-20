package com.jmip.repository;

import com.jmip.entity.Job;
import com.jmip.repository.projection.CategoryCountRow;
import com.jmip.repository.projection.CompanyDemandRow;
import com.jmip.repository.projection.ExperienceCountRow;
import com.jmip.repository.projection.LocationDemandRow;
import com.jmip.repository.projection.SalaryRangeRow;
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

    // -------------------------------------------------------------- V4: job categories

    /**
     * Postings per category. Unclassified postings are excluded rather than counted as a
     * category of their own, so the percentages describe the classified corpus.
     */
    @Query("""
            select new com.jmip.repository.projection.CategoryCountRow(j.jobCategory, count(j.id))
            from Job j
            where j.jobCategory is not null
            group by j.jobCategory
            order by count(j.id) desc, j.jobCategory asc
            """)
    List<CategoryCountRow> countByCategory();

    @Query("select count(j.id) from Job j where j.jobCategory is not null")
    long countClassifiedJobs();

    @Query("select count(j.id) from Job j where j.jobCategory = :category")
    long countJobsInCategory(@Param("category") String category);

    /** The skills most asked for within one category. */
    @Query("""
            select new com.jmip.repository.projection.SkillDemandRow(s.id, s.name, s.category, count(j.id))
            from Job j join j.skills s
            where j.jobCategory = :category
            group by s.id, s.name, s.category
            order by count(j.id) desc, s.name asc
            """)
    List<SkillDemandRow> findSkillsForCategory(@Param("category") String category, Pageable limit);

    /** Where postings in one category are concentrated. Remote postings have no location. */
    @Query("""
            select new com.jmip.repository.projection.LocationDemandRow(
                l.id, l.city, l.state, l.country, count(j.id))
            from Job j join j.location l
            where j.jobCategory = :category
            group by l.id, l.city, l.state, l.country
            order by count(j.id) desc, l.country asc
            """)
    List<LocationDemandRow> findLocationsForCategory(@Param("category") String category, Pageable limit);

    /** Which companies are hiring for one category. */
    @Query("""
            select new com.jmip.repository.projection.CompanyDemandRow(
                c.id, c.name, c.industry, c.website, count(j.id))
            from Job j join j.company c
            where j.jobCategory = :category
            group by c.id, c.name, c.industry, c.website
            order by count(j.id) desc, c.name asc
            """)
    List<CompanyDemandRow> findCompaniesForCategory(@Param("category") String category, Pageable limit);

    @Query("select count(j.id) from Job j where j.company.id = :companyId")
    long countJobsForCompany(@Param("companyId") Long companyId);

    @Query("select count(j.id) from Job j where j.location.id = :locationId")
    long countJobsForLocation(@Param("locationId") Long locationId);

    // ------------------------------------------------------------- V5: assistant queries

    /**
     * Where postings asking for one skill are concentrated.
     *
     * <p>The per-category equivalents above answer "which cities want Data Engineers";
     * this answers "which cities want Java". Same shape, different filter — a skill is not
     * a category, and neither can stand in for the other.
     */
    @Query("""
            select new com.jmip.repository.projection.LocationDemandRow(
                l.id, l.city, l.state, l.country, count(j.id))
            from Job j join j.location l join j.skills s
            where lower(s.name) = lower(:skill)
            group by l.id, l.city, l.state, l.country
            order by count(j.id) desc, l.country asc
            """)
    List<LocationDemandRow> findLocationsForSkill(@Param("skill") String skill, Pageable limit);

    /** Which companies ask for one skill. */
    @Query("""
            select new com.jmip.repository.projection.CompanyDemandRow(
                c.id, c.name, c.industry, c.website, count(j.id))
            from Job j join j.company c join j.skills s
            where lower(s.name) = lower(:skill)
            group by c.id, c.name, c.industry, c.website
            order by count(j.id) desc, c.name asc
            """)
    List<CompanyDemandRow> findCompaniesForSkill(@Param("skill") String skill, Pageable limit);

    /** How many postings ask for one skill, for comparing two of them. */
    @Query("""
            select count(distinct j.id) from Job j join j.skills s
            where lower(s.name) = lower(:skill)
            """)
    long countJobsWithSkill(@Param("skill") String skill);

    /**
     * Stated salaries, grouped by currency, optionally within one category.
     *
     * <p>Grouped and never pooled. The dataset carries eight currencies and no exchange
     * rates, so a single "average salary" across them would be arithmetic on
     * incomparable units. Postings that state no minimum are excluded rather than counted
     * as zero, and the count comes back with the figures so a caller can see how thin the
     * sample is.
     *
     * @param category exact job category, or null for every posting
     */
    @Query("""
            select new com.jmip.repository.projection.SalaryRangeRow(
                trim(j.currency), count(j.id), min(j.salaryMin), max(j.salaryMax),
                avg(j.salaryMin), avg(j.salaryMax))
            from Job j
            where j.salaryMin is not null and j.currency is not null
              and (:category is null or j.jobCategory = :category)
            group by trim(j.currency)
            order by count(j.id) desc, trim(j.currency) asc
            """)
    List<SalaryRangeRow> findSalaryRanges(@Param("category") String category);
}
