package io.reliablepush.devices;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliablepush.support.PostgresTestBase;
import io.reliablepush.support.TestDevices;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * The active-device window (sidecar revision spec 2.3) equals token acceptance on the grace routes: a token is
 * accepted until {@code exp + skew + grace} inclusive ({@link DeviceTokenService#SKEW}), so a device counts as active
 * exactly as long.
 */
@ActiveProfiles("test")
class ActiveDeviceWindowIT extends PostgresTestBase {

    private static final Duration GRACE = Duration.ofDays(90);

    @Autowired
    DeviceRepository repository;

    @Autowired
    JdbcClient jdbc;

    private boolean activeWithTokenExpiredAt(Instant now, Duration before) {
        String user = "window-" + UUID.randomUUID();
        TestDevices.insert(jdbc, "shop", user, now.minus(before), null);
        return repository.hasActiveDevice("shop", user, now, GRACE);
    }

    @Test
    void activeUpToTheLastInstantATokenIsAcceptedAndNotAfter() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Duration skew = DeviceTokenService.SKEW;

        assertThat(activeWithTokenExpiredAt(now, GRACE)).as("at exp + grace, inside the skew").isTrue();
        assertThat(activeWithTokenExpiredAt(now, GRACE.plus(skew).minusSeconds(1))).isTrue();
        assertThat(activeWithTokenExpiredAt(now, GRACE.plus(skew))).as("exp + skew + grace == now: still accepted").isTrue();
        assertThat(activeWithTokenExpiredAt(now, GRACE.plus(skew).plusSeconds(1))).as("past acceptance").isFalse();
    }
}
