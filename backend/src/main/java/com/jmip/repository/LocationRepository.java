package com.jmip.repository;

import com.jmip.entity.Location;
import com.jmip.repository.projection.LocationDemandRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

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

    /**
     * Every location whose city, state or country is exactly this name.
     *
     * <p>A question says "Bengaluru" or "India" without saying which kind of place it is,
     * so all three are tried. Several rows can match one name — a country has many cities
     * — and the caller decides what to do with that.
     */
    @Query("""
            select l from Location l
            where lower(l.city) = lower(:name)
               or lower(l.state) = lower(:name)
               or lower(l.country) = lower(:name)
            """)
    List<Location> findByPlaceName(@Param("name") String name);
}
