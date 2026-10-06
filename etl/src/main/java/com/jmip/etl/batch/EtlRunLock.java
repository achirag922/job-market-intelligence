package com.jmip.etl.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * V9.9: one ETL run at a time against a database. A run holds a PostgreSQL session advisory lock
 * on its own connection from start to finish; a second run started meanwhile (a double cron entry,
 * a manual run during a scheduled one) fails at once instead of loading the same feed concurrently.
 * The lock belongs to the connection, so a crashed run releases it and the next run can restart.
 */
@Component
public class EtlRunLock {

    /** The advisory lock key, shared with anything that must not overlap an ETL run. */
    public static final String KEY = "jmip:etl";

    private static final Logger log = LoggerFactory.getLogger(EtlRunLock.class);

    private final DataSource dataSource;
    private Connection held;

    public EtlRunLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** @return false when another run holds the lock */
    public synchronized boolean acquire() {
        if (held != null) {
            return true;
        }
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            if (call(connection, "SELECT pg_try_advisory_lock(hashtext(?))")) {
                held = connection;
                return true;
            }
            connection.close();
            return false;
        } catch (SQLException failure) {
            closeQuietly(connection);
            throw new IllegalStateException("Could not check for a concurrent ETL run", failure);
        }
    }

    public synchronized void release() {
        if (held == null) {
            return;
        }
        try {
            call(held, "SELECT pg_advisory_unlock(hashtext(?))");
        } catch (SQLException failure) {
            // Closing the connection below releases the lock anyway.
            log.warn("ETL run lock could not be released explicitly: {}", failure.getClass().getSimpleName());
        } finally {
            closeQuietly(held);
            held = null;
        }
    }

    private static boolean call(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Already unusable; the pool discards it.
            }
        }
    }
}
