package io.reliablepush.devices;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliablepush.support.PostgresTestBase;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

/** The token service is wired in the full application with the generated test key from application-test.yml. */
@ActiveProfiles("test")
class DeviceTokenWiringIT extends PostgresTestBase {

    @Autowired
    DeviceTokenService tokens;

    @Test
    void issuesAndVerifiesWithTheConfiguredTestKey() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        DeviceClaims claims = new DeviceClaims("shop", "user-1", UUID.randomUUID(), 0, now, now.plus(Duration.ofDays(90)));

        assertThat(tokens.verify(tokens.issue(claims).token())).isEqualTo(claims);
    }
}
