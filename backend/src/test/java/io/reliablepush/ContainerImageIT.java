package io.reliablepush;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import io.reliablepush.devices.SigningKeys;
import io.reliablepush.support.TestKeys;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the built container image (profile {@code image}: {@code ./mvnw verify -Pimage}) the way OpenShift does: an
 * arbitrary UID in group 0, a read-only root filesystem and a tmpfs {@code /tmp}, configured only through environment
 * variables. Excluded from the default build (JUnit tag {@code image}).
 */
@Tag("image")
class ContainerImageIT {

    /** What OpenShift's restricted SCC assigns: a random UID from the project range, always group 0. */
    private static final String ARBITRARY_USER = "1000870000:0";

    /** Spec 9 budget: uncompressed image size (sum of layer sizes), decimal megabytes. */
    private static final long MAX_IMAGE_BYTES = 250_000_000L;
    private static final String IMAGE = System.getProperty("image.name", "reliable-push-backend:0.1.0-SNAPSHOT");

    private static Network network;
    private static PostgreSQLContainer postgres;
    private static GenericContainer<?> app;

    @BeforeAll
    static void startApplication() {
        network = Network.newNetwork();
        postgres = new PostgreSQLContainer("postgres:16-alpine").withNetwork(network).withNetworkAliases("postgres");
        postgres.start();

        SigningKeys.PemPair deviceKey = TestKeys.newEcPemPair();
        app = new GenericContainer<>(IMAGE)
                .withNetwork(network)
                .withExposedPorts(8080)
                .withCreateContainerCmdModifier(cmd -> cmd.withUser(ARBITRARY_USER))
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withReadonlyRootfs(true))
                .withTmpFs(Map.of("/tmp", "rw,nosuid,size=64m"))
                .withEnv("SPRING_DATASOURCE_URL", "jdbc:postgresql://postgres:5432/" + postgres.getDatabaseName())
                .withEnv("SPRING_DATASOURCE_USERNAME", postgres.getUsername())
                .withEnv("SPRING_DATASOURCE_PASSWORD", postgres.getPassword())
                // device-token signing key: the refs point at these (throwaway) environment variables
                .withEnv("RP_DEVICE_KEY_PRIVATE", deviceKey.privateKeyPem())
                .withEnv("RP_DEVICE_KEY_PUBLIC", deviceKey.publicKeyPem())
                .withEnv("RELIABLE_PUSH_DEVICE_TOKEN_CURRENT_KID", "k1")
                .withEnv("RELIABLE_PUSH_DEVICE_TOKEN_SIGNING_KEYS_0_KID", "k1")
                .withEnv("RELIABLE_PUSH_DEVICE_TOKEN_SIGNING_KEYS_0_PRIVATE_KEY_PEM_REF", "env:RP_DEVICE_KEY_PRIVATE")
                .withEnv("RELIABLE_PUSH_DEVICE_TOKEN_SIGNING_KEYS_0_PUBLIC_KEY_PEM_REF", "env:RP_DEVICE_KEY_PUBLIC")
                // one app; startup does no network call to the identity provider or the fallback endpoint
                .withEnv("RP_HOOK_SECRET", "container-test-hook-secret")
                .withEnv("RELIABLE_PUSH_APPS_SHOP_SENDER_API_KEY_SHA256", "0".repeat(64))
                .withEnv("RELIABLE_PUSH_APPS_SHOP_IDENTITY_TYPE", "OIDC")
                .withEnv("RELIABLE_PUSH_APPS_SHOP_IDENTITY_ISSUER", "https://idp.container-test.example")
                .withEnv("RELIABLE_PUSH_APPS_SHOP_IDENTITY_AUDIENCE", "shop-mobile")
                .withEnv("RELIABLE_PUSH_APPS_SHOP_FALLBACK_URL", "https://hooks.container-test.example/fallback")
                .withEnv("RELIABLE_PUSH_APPS_SHOP_FALLBACK_HMAC_SECRET_REF", "env:RP_HOOK_SECRET")
                .waitingFor(Wait.forHttp("/actuator/health/readiness")
                        .forPort(8080)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(60)));
        app.start();
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.stop();
        }
        if (postgres != null) {
            postgres.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    @Test
    void startsAsArbitraryNonRootUid() throws Exception {
        // readiness (200 within 60 s) is the wait strategy of the container; here: the process really is that UID
        var uid = app.execInContainer("id", "-u");
        assertThat(uid.getExitCode()).isZero();
        assertThat(uid.getStdout().trim()).isEqualTo("1000870000").isNotEqualTo("0");
        var gid = app.execInContainer("id", "-g");
        assertThat(gid.getStdout().trim()).isEqualTo("0");

        // PID 1 is the JVM (exec-form ENTRYPOINT, no wrapper script): real, effective, saved and fs UID
        var status = app.execInContainer("cat", "/proc/1/status");
        assertThat(status.getExitCode()).isZero();
        assertThat(status.getStdout()).isNotBlank().containsPattern("(?m)^Name:\\s+java$");
        assertThat(status.getStdout().lines().filter(l -> l.startsWith("Uid:")).findFirst())
                .hasValueSatisfying(line -> assertThat(line.substring(4).trim().split("\\s+"))
                        .containsOnly("1000870000"));
    }

    @Test
    void imageIsSmallerThan250Mb() {
        // inspect's Size is daemon dependent (compressed with the containerd image store, uncompressed with overlay2):
        // the layer sizes from the history are uncompressed on both
        DockerClient docker = DockerClientFactory.instance().client();
        long size = docker.imageHistoryCmd(IMAGE).exec().stream()
                .mapToLong(layer -> layer.getSize() == null ? 0 : layer.getSize())
                .sum();
        assertThat(size).as("uncompressed image size in bytes (sum of layers) = %d, budget %d", size, MAX_IMAGE_BYTES).isPositive().isLessThan(MAX_IMAGE_BYTES);
    }

    @Test
    void rootFilesystemIsReadOnlyExceptTmp() throws Exception {
        assertThat(app.execInContainer("touch", "/tmp/writable").getExitCode()).isZero();
        assertThat(app.execInContainer("touch", "/app/not-writable").getExitCode()).isNotZero();
        assertThat(app.execInContainer("touch", "/etc/not-writable").getExitCode()).isNotZero();
    }

    @Test
    void stdoutIsJsonLines() {
        assertThat(app.getLogs(OutputFrame.OutputType.STDOUT).lines().filter(line -> !line.isBlank()))
                .as("stdout is JSON lines only")
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line).startsWith("{"));
    }
}
