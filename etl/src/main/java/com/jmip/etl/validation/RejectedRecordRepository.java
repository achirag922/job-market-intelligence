package com.jmip.etl.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmip.etl.raw.RawJobRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists rejected records to {@code etl_rejected_record}.
 *
 * <p>Writes run in their own transaction. A rejection is evidence about a chunk that may
 * itself be rolling back, and evidence that disappears with the thing it describes is
 * worthless — that is exactly the silent discard this table exists to prevent.
 */
@Repository
public class RejectedRecordRepository {

    private static final Logger log = LoggerFactory.getLogger(RejectedRecordRepository.class);

    private static final String INSERT = """
            INSERT INTO etl_rejected_record (job_name, job_execution_id, rejection_reason, original_record)
            VALUES (?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RejectedRecordRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(String jobName, long jobExecutionId, String reason, RawJobRecord record) {
        jdbcTemplate.update(INSERT, jobName, jobExecutionId, reason, serialize(record));
    }

    private String serialize(RawJobRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException e) {
            // Never lose the rejection because its payload would not serialise.
            log.warn("Could not serialise rejected record, storing toString() instead", e);
            return String.valueOf(record);
        }
    }
}
