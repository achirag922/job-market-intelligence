package com.jmip.etl.reprocess;

import com.jmip.etl.model.JobClassification;

import java.util.Set;

/**
 * What reprocessing decided about one existing posting: the skills now recognised in it
 * and the category it falls into.
 */
public record ReprocessedJob(long id, Set<String> skills, JobClassification classification) {
}
