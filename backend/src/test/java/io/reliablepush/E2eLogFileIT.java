package io.reliablepush;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import io.reliablepush.support.PostgresTestBase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.NestedTestConfiguration.EnclosingConfiguration;
import org.springframework.test.context.TestPropertySource;

/**
 * In the {@code e2e}/{@code demo} profiles the backend also appends its ECS JSON lines to a file (the harness tails it
 * from a shared volume); without those profiles no file is created. The path comes from the {@code rp.log.file} system
 * property, which must be set before the application context (and so the Log4j reconfiguration) starts.
 */
@ActiveProfiles({"test", "e2e"})
// distinct property => fresh application context, so Log4j is reconfigured with this test's file path
@TestPropertySource(properties = {"rp.test.fresh-context=e2e-log-file", "reliable-push.worker.poll-interval=1d"})
class E2eLogFileIT extends PostgresTestBase {

    private static Path dir;
    private static Path logFile;

    @BeforeAll
    static void pointLogFileAtTempDir() throws IOException {
        dir = Files.createTempDirectory("rp-e2e-log");
        logFile = dir.resolve("backend.json");
        System.setProperty("rp.log.file", logFile.toString());
    }

    @AfterAll
    static void cleanUp() throws IOException {
        System.clearProperty("rp.log.file");
        restoreDefaultLoggingConfiguration();
        deleteRecursively(dir);
    }

    @Autowired
    private ConfigurableEnvironment environment;

    @BeforeEach
    void reinitialiseLogging() {
        applyLoggingConfiguration(environment);
    }

    @Test
    void writesJsonLinesToFileInE2eProfile() {
        http.get().uri("/actuator/health").retrieve().toBodilessEntity();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(logFile).exists();
            List<String> lines = Files.readAllLines(logFile);
            assertThat(lines).isNotEmpty();
            assertThat(lines).anySatisfy(line -> {
                Map<String, Object> event = JsonPath.read(line, "$");
                assertThat(event).containsEntry("event.action", "api.request").containsEntry("service.name", "reliable-push");
                assertThat(event.get("service.version")).asString().isNotBlank();
            });
        });
    }

    /** Without e2e/demo the file appender is not configured: nothing is created. */
    @Nested
    @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
    @ActiveProfiles({"test"})
    @TestPropertySource(properties = {"rp.test.fresh-context=e2e-log-file-absent", "reliable-push.worker.poll-interval=1d"})
    class WithoutProfile extends PostgresTestBase {

        private static Path otherDir;
        private static Path otherFile;

        @BeforeAll
        static void pointLogFileAtOtherTempDir() throws IOException {
            otherDir = Files.createTempDirectory("rp-no-e2e-log");
            otherFile = otherDir.resolve("backend.json");
            System.setProperty("rp.log.file", otherFile.toString());
        }

        @AfterAll
        static void cleanUpOther() throws IOException {
            System.clearProperty("rp.log.file");
            restoreDefaultLoggingConfiguration();
            deleteRecursively(otherDir);
        }

        @Autowired
        private ConfigurableEnvironment environment;

        @BeforeEach
        void reinitialiseLogging() {
            applyLoggingConfiguration(environment);
        }

        @Test
        void notWrittenWithoutProfile() throws InterruptedException {
            http.get().uri("/actuator/health").retrieve().toBodilessEntity();
            Thread.sleep(500);

            assertThat(otherFile).doesNotExist();
        }
    }

    /**
     * Spring Boot initialises Log4j once per JVM (the first application context wins, later cached contexts keep that
     * configuration), so the profiles of this test's context only take effect after an explicit re-initialisation.
     */
    private static void applyLoggingConfiguration(ConfigurableEnvironment environment) {
        LoggingSystem loggingSystem = LoggingSystem.get(E2eLogFileIT.class.getClassLoader());
        loggingSystem.cleanUp();
        loggingSystem.initialize(new LoggingInitializationContext(environment), null, null);
    }

    /** Leaves the JVM-wide Log4j as the other ITs expect it: profile {@code test} only, JSON on stdout, no file. */
    private static void restoreDefaultLoggingConfiguration() {
        StandardEnvironment plain = new StandardEnvironment();
        plain.setActiveProfiles("test");
        applyLoggingConfiguration(plain);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
