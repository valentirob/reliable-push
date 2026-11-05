package io.reliablepush.harness.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvironmentSecretsTest {

    // test-only placeholder, not a real key
    static final String BODY_1 = "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgPLACEHOLDERplaceholder";
    static final String BODY_2 = "oUQDQgAEtestonlytestonlytestonlytestonlytestonly";
    /** As GenSecrets writes it into secrets.env: single-quoted, with literal backslash-n sequences. */
    static final String RAW_PEM = "-----BEGIN PRIVATE KEY-----\\n" + BODY_1 + "\\n" + BODY_2 + "\\n-----END PRIVATE KEY-----\\n";
    static final String DECODED_PEM = "-----BEGIN PRIVATE KEY-----\n" + BODY_1 + "\n" + BODY_2 + "\n-----END PRIVATE KEY-----\n";

    @Test
    void signingKeyReferencedByEnvRefAndDatabasePassword() {
        Map<String, String> env = Map.of(
                "RELIABLEPUSH_DEVICETOKEN_SIGNINGKEYS_0_PRIVATEKEYPEMREF", "env:RP_DEVICE_KEY_E2E",
                "RELIABLEPUSH_DEVICETOKEN_SIGNINGKEYS_0_PUBLICKEYPEMREF", "env:RP_DEVICE_PUB_E2E",
                "RP_DEVICE_KEY_E2E", RAW_PEM,
                "RP_DEVICE_PUB_E2E", "-----BEGIN PUBLIC KEY-----\\nPUBLICpublicPUBLIC\\n-----END PUBLIC KEY-----\\n",
                "SPRING_DATASOURCE_PASSWORD", "db-password-0123456789abcdef");

        var secrets = EnvironmentSecrets.from(env);

        assertThat(secrets).contains(RAW_PEM, DECODED_PEM, DECODED_PEM.strip(), BODY_1 + BODY_2,
                "db-password-0123456789abcdef");
        assertThat(String.join(" ", secrets)).doesNotContain("PUBLIC");
    }

    @Test
    void inlinePemAndSeveralKeys() {
        Map<String, String> env = Map.of(
                "RELIABLEPUSH_DEVICETOKEN_SIGNINGKEYS_0_PRIVATEKEYPEM", DECODED_PEM,
                "RELIABLEPUSH_DEVICETOKEN_SIGNINGKEYS_1_PRIVATEKEYPEMREF", "env:OLD_KEY",
                "OLD_KEY", "-----BEGIN PRIVATE KEY-----\nOLDKEYBODYoldkeybody\n-----END PRIVATE KEY-----");

        assertThat(EnvironmentSecrets.from(env)).contains(DECODED_PEM, BODY_1 + BODY_2, "OLDKEYBODYoldkeybody");
    }

    @Test
    void absentOrUnresolvableValuesAreSkippedSilently() {
        assertThat(EnvironmentSecrets.from(Map.of())).isEmpty();
        assertThat(EnvironmentSecrets.from(Map.of(
                "RELIABLEPUSH_DEVICETOKEN_SIGNINGKEYS_0_PRIVATEKEYPEMREF", "env:MISSING",
                "RELIABLEPUSH_DEVICETOKEN_SIGNINGKEYS_1_PRIVATEKEYPEMREF", "file:/run/secrets/key",
                "SPRING_DATASOURCE_PASSWORD", " "))).isEmpty();
    }
}
