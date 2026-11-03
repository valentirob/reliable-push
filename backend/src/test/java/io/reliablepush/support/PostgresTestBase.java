package io.reliablepush.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.web.client.RestClient;

/**
 * Base class for every integration test: full application on a random port against a real
 * PostgreSQL (Testcontainers). The container is a JVM-wide singleton so all {@code *IT} classes
 * share it; it is stopped by Testcontainers' Ryuk when the JVM exits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class PostgresTestBase {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @LocalServerPort
    protected int port;

    /** HTTP client bound to the random port of the running application. */
    protected RestClient http;

    @BeforeEach
    void createHttpClient() {
        http = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }
}
