package io.reliablepush.support;

import io.reliablepush.devices.SigningKeys;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

    /** Generated once per JVM; never written to disk. Referenced by {@code env:RP_TEST_DEVICE_KEY_K1/PUB_K1} in application-test.yml. */
    private static final SigningKeys.PemPair DEVICE_KEY_K1 = TestKeys.newEcPemPair();

    @DynamicPropertySource
    static void deviceTokenKey(DynamicPropertyRegistry registry) {
        registry.add("RP_TEST_DEVICE_KEY_K1", DEVICE_KEY_K1::privateKeyPem);
        registry.add("RP_TEST_DEVICE_PUB_K1", DEVICE_KEY_K1::publicKeyPem);
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
