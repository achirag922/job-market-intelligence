package com.jmip.repository;

import com.jmip.entity.JobClassificationSignal;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface JobClassificationSignalRepository
        extends Repository<JobClassificationSignal, JobClassificationSignal.Key> {

    /** Strongest evidence first, so the explanation leads with what mattered most. */
    @Query("""
            select s from JobClassificationSignal s
            where s.jobId = :jobId
            order by s.weight desc, s.signalValue asc
            """)
    List<JobClassificationSignal> findByJobId(@Param("jobId") Long jobId);
}
