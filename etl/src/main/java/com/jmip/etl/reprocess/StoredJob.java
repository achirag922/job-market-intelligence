package com.jmip.etl.reprocess;

/**
 * A posting as it already exists in the database, read for reprocessing.
 *
 * <p>Only the three columns reprocessing needs. Reading whole rows would move far more
 * data than the work requires, and descriptions are the largest column in the table.
 */
public record StoredJob(long id, String title, String description) {
}
