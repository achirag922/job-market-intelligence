package com.jmip.repository;

import com.jmip.entity.Location;
import com.jmip.repository.projection.LocationDemandRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LocationRepository extends JpaRepository<Location, Long> {

    Page<Location> findByCountryContainingIgnoreCase(String country, Pageable pageable);

    @Query(value = """
            select new com.jmip.repository.projection.LocationDemandRow(
                l.id, l.city, l.state, l.country, count(j.id))
            from Job j join j.location l
            group by l.id, l.city, l.state, l.country
            order by count(j.id) desc, l.country asc
            """,
            countQuery = "select count(distinct l.id) from Job j join j.location l")
    Page<LocationDemandRow> findLocationDemand(Pageable pageable);
}
