package com.jmip;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies that the application context starts and that Flyway migrates cleanly
 * against a real PostgreSQL instance.
 */
@SpringBootTest
@Testcontainers
class ApplicationContextTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Test
    void contextLoads() {
        // Fails if any bean, the datasource, or a Flyway migration is misconfigured.
    }
}
