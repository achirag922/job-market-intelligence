package com.jmip.repository;

import com.jmip.entity.Company;
import com.jmip.repository.projection.CompanyDemandRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    Page<Company> findByNameContainingIgnoreCase(String name, Pageable pageable);

    @Query("select count(j) from Job j where j.company.id = :companyId")
    long countJobs(@Param("companyId") Long companyId);

    /**
     * Ordered inside the query rather than by a {@code Sort}, because ranking by posting
     * count is the only ordering this endpoint has any reason to offer.
     */
    @Query(value = """
            select new com.jmip.repository.projection.CompanyDemandRow(
                c.id, c.name, c.industry, c.website, count(j.id))
            from Job j join j.company c
            group by c.id, c.name, c.industry, c.website
            order by count(j.id) desc, c.name asc
            """,
            countQuery = "select count(distinct c.id) from Job j join j.company c")
    Page<CompanyDemandRow> findCompanyDemand(Pageable pageable);
}
