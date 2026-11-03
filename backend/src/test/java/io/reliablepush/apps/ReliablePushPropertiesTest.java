package io.reliablepush.apps;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliablepush.shared.time.TimeScale;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class ReliablePushPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReliablePushProperties.class)
    @Import({AppRegistry.class, TimeScale.class})
    static class Config {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class)
            .withPropertyValues(AppFixtures.HOOK_SECRET_ENV + "=generated-test-hook-secret");

    private String[] asProperties(Map<String, Object> map) {
        return map.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new);
    }

    private Map<String, Object> shop(Consumer<Map<String, Object>> customizer) {
        Map<String, Object> p = new HashMap<>(AppFixtures.minimalApp("shop", "k-123"));
        customizer.accept(p);
        return p;
    }

    private static final String SHOP = "reliable-push.apps.shop.";

    @Test
    void rejectsWindowShorterThanBackoff() {
        Map<String, Object> p = shop(m -> {
            m.put(SHOP + "fallback.backoff", "1m,2m,4m,8m");
            m.put(SHOP + "fallback.trigger-window-minutes", "14");
        });

        runner.withPropertyValues(asProperties(p)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("trigger-window-minutes");
        });
    }

    @Test
    void acceptsWindowEqualToCumulativeBackoff() {
        Map<String, Object> p = shop(m -> {
            m.put(SHOP + "fallback.backoff", "1m,2m,4m,8m");
            m.put(SHOP + "fallback.trigger-window-minutes", "15");
        });

        runner.withPropertyValues(asProperties(p)).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void shortBackoffListRepeatsItsLastDelay() {
        // 5 attempts = 4 gaps: 1m + 2m + 2m + 2m = 7m
        Map<String, Object> p = shop(m -> {
            m.put(SHOP + "fallback.backoff", "1m,2m");
            m.put(SHOP + "fallback.trigger-window-minutes", "6");
        });

        runner.withPropertyValues(asProperties(p)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("trigger-window-minutes");
        });
    }

    @Test
    void fastClockKeepsWindowAndBackoffInTheSameUnits() {
        // minute-duration=1s scales window and backoff alike, so the check compares them unscaled: 20 >= 15 starts, 14 < 15 fails
        ApplicationContextRunner fast = runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("e2e"))
                .withPropertyValues("reliable-push.testing.minute-duration=1s");

        fast.withPropertyValues(asProperties(shop(m -> m.put(SHOP + "fallback.trigger-window-minutes", "20"))))
                .run(context -> assertThat(context).hasNotFailed());

        fast.withPropertyValues(asProperties(shop(m -> m.put(SHOP + "fallback.trigger-window-minutes", "14"))))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure())).contains("trigger-window-minutes");
                });
    }

    @Test
    void fastClockInForbiddenProfileFailsWithTimeScaleMessage() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("api"))
                .withPropertyValues("reliable-push.testing.minute-duration=1s")
                .withPropertyValues(asProperties(AppFixtures.minimalApp("shop", "k-123")))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure()))
                            .contains("minute-duration is only allowed in profiles e2e or demo");
                });
    }

    @Test
    void secretWithPlaceholderSyntaxResolvesVerbatimAndNeverLeaks() {
        String secret = "abc${nope}SECRETTAIL";
        Map<String, Object> p = AppFixtures.minimalApp("shop", "k-123");

        runner.withPropertyValues(AppFixtures.HOOK_SECRET_ENV + "=" + secret)
                .withPropertyValues(asProperties(p))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AppConfig app = context.getBean(AppRegistry.class).byId("shop").orElseThrow();
                    assertThat(app.fallback().hmacSecret().value()).isEqualTo(secret);
                });

        // an unrelated startup failure must not quote the secret either
        Map<String, Object> bad = shop(m -> m.put(SHOP + "fallback.trigger-window-minutes", "1"));
        runner.withPropertyValues(AppFixtures.HOOK_SECRET_ENV + "=" + secret)
                .withPropertyValues(asProperties(bad))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure())).doesNotContain("SECRETTAIL");
                });
    }

    @Test
    void defaultsApplied() {
        runner.withPropertyValues(asProperties(AppFixtures.minimalApp("shop", "k-123"))).run(context -> {
            assertThat(context).hasNotFailed();
            AppConfig app = context.getBean(AppRegistry.class).byId("shop").orElseThrow();
            assertThat(app.deviceTokenValidity()).isEqualTo(Duration.ofDays(90));
            assertThat(app.defaultTimeoutMinutes()).isEqualTo(10);
            assertThat(app.defaultPolicy()).isEqualTo("NOT_DELIVERED");
            assertThat(app.identity().userClaim()).isEqualTo("sub");
            assertThat(app.fallback().maxAttempts()).isEqualTo(5);
            assertThat(app.fallback().triggerWindowMinutes()).isEqualTo(20);
            assertThat(app.fallback().backoff())
                    .containsExactly(Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(4), Duration.ofMinutes(8));
            assertThat(app.fallback().httpTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(app.rateLimits().devicePerMinute()).isEqualTo(60);
            assertThat(app.rateLimits().senderPerMinute()).isEqualTo(6000);

            ReliablePushProperties props = context.getBean(ReliablePushProperties.class);
            assertThat(props.retentionDays()).isEqualTo(30);
            assertThat(props.worker().pollInterval()).isEqualTo(Duration.ofSeconds(5));
            assertThat(props.worker().batchSize()).isEqualTo(100);
            assertThat(props.worker().lease()).isEqualTo(Duration.ofMinutes(2));
            assertThat(props.deviceToken().revocationCacheTtl()).isEqualTo(Duration.ofSeconds(60));
            assertThat(props.testing().minuteDuration()).isNull();
        });
    }

    @Test
    void unitLessMinuteDurationsBindAsMinutes() {
        Map<String, Object> p = shop(m -> {
            m.put(SHOP + "fallback.backoff", "1,2,4,8");
            m.put(SHOP + "device-token-validity", "30");
            m.put("reliable-push.worker.lease", "3");
        });

        runner.withPropertyValues(asProperties(p)).run(context -> {
            assertThat(context).hasNotFailed();
            AppConfig app = context.getBean(AppRegistry.class).byId("shop").orElseThrow();
            assertThat(app.fallback().backoff())
                    .containsExactly(Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(4), Duration.ofMinutes(8));
            assertThat(app.deviceTokenValidity()).isEqualTo(Duration.ofDays(30));
            assertThat(context.getBean(ReliablePushProperties.class).worker().lease()).isEqualTo(Duration.ofMinutes(3));
        });
    }

    @Test
    void unitLessBackoffAsIndexedListBindsAsMinutes() {
        // the YAML list form (backoff: [1, 2]) arrives as indexed properties
        Map<String, Object> p = shop(m -> {
            m.put(SHOP + "fallback.backoff[0]", "1");
            m.put(SHOP + "fallback.backoff[1]", "2");
        });

        runner.withPropertyValues(asProperties(p)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AppRegistry.class).byId("shop").orElseThrow().fallback().backoff())
                    .containsExactly(Duration.ofMinutes(1), Duration.ofMinutes(2));
        });
    }

    @Test
    void malformedBackoffEntryFailsTheStart() {
        Map<String, Object> p = shop(m -> m.put(SHOP + "fallback.backoff", "1m,soon"));

        runner.withPropertyValues(asProperties(p)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("fallback.backoff").contains("soon");
        });
    }

    @Test
    void explicitUnitsStillWin() {
        Map<String, Object> p = shop(m -> {
            m.put(SHOP + "fallback.backoff", "1m,2s,90s,1h");
            m.put(SHOP + "fallback.trigger-window-minutes", "70");
            m.put(SHOP + "device-token-validity", "12h");
            m.put("reliable-push.worker.lease", "30s");
        });

        runner.withPropertyValues(asProperties(p)).run(context -> {
            assertThat(context).hasNotFailed();
            AppConfig app = context.getBean(AppRegistry.class).byId("shop").orElseThrow();
            assertThat(app.fallback().backoff()).containsExactly(
                    Duration.ofMinutes(1), Duration.ofSeconds(2), Duration.ofSeconds(90), Duration.ofHours(1));
            assertThat(app.deviceTokenValidity()).isEqualTo(Duration.ofHours(12));
            assertThat(context.getBean(ReliablePushProperties.class).worker().lease()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    void workerMaxConcurrencyDefaultsToTheBatchSizeAndMustBePositive() {
        Map<String, Object> p = AppFixtures.minimalApp("shop", "k-123");

        runner.withPropertyValues(asProperties(p)).withPropertyValues("reliable-push.worker.batch-size=40").run(context ->
                assertThat(context.getBean(ReliablePushProperties.class).worker().maxConcurrency()).isEqualTo(40));
        runner.withPropertyValues(asProperties(p)).withPropertyValues("reliable-push.worker.max-concurrency=7").run(context ->
                assertThat(context.getBean(ReliablePushProperties.class).worker().maxConcurrency()).isEqualTo(7));
        runner.withPropertyValues(asProperties(p)).withPropertyValues("reliable-push.worker.max-concurrency=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("maxConcurrency");
        });
    }

    @Test
    void zeroAppsFailTheStart() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure()))
                    .contains("no apps configured under reliable-push.apps")
                    .contains("RELIABLEPUSH_APPS_<ID>_");
        });
    }

    @Test
    void environmentVariablesBindAppsOnlyInTheDashlessForm() {
        runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(asEnvironment("RELIABLEPUSH_")))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AppRegistry.class).byId("shop")).isPresent();
                });
        // RELIABLE_PUSH_APPS_... maps to reliable.push.apps..., not reliable-push.apps...: the start must fail loudly
        runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(asEnvironment("RELIABLE_PUSH_")))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure())).contains("no apps configured");
                });
    }

    /**
     * The minimal shop app as environment variables, with {@code prefix} in place of {@code reliable-push.}. The source
     * name must end in {@code -systemEnvironment} for Spring to apply its environment-variable name mapping.
     */
    private static SystemEnvironmentPropertySource asEnvironment(String prefix) {
        Map<String, Object> env = new HashMap<>();
        AppFixtures.minimalApp("shop", "k-123").forEach((key, value) -> env.put(
                prefix + key.substring("reliable-push.".length()).replace('.', '_').replace("-", "").toUpperCase(), value));
        return new SystemEnvironmentPropertySource("test-" + StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env);
    }

    @Test
    void rejectsInlineSecretWithoutEchoingIt() {
        Map<String, Object> p = shop(m -> m.put(SHOP + "fallback.hmac-secret-ref", "super-secret-inline-value"));

        runner.withPropertyValues(asProperties(p)).run(context -> {
            assertThat(context).hasFailed();
            String message = rootMessage(context.getStartupFailure());
            assertThat(message).contains("hmac-secret-ref").contains("env:");
            assertThat(message).doesNotContain("super-secret-inline-value");
        });
    }

    @Test
    void rejectsReferenceToUnsetEnvironmentVariable() {
        Map<String, Object> p = shop(m -> m.put(SHOP + "fallback.hmac-secret-ref", "env:RP_TEST_NOT_SET"));

        runner.withPropertyValues(asProperties(p)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("RP_TEST_NOT_SET").contains("hmac-secret-ref");
        });
    }

    @Test
    void signedGrantRequiresGrantSecretRef() {
        Map<String, Object> p = shop(m -> m.put(SHOP + "identity.type", "signed-grant"));

        runner.withPropertyValues(asProperties(p)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("grant-secret-ref");
        });
    }

    @Test
    void signedGrantResolvesGrantSecret() {
        Map<String, Object> p = shop(m -> {
            m.put(SHOP + "identity.type", "signed-grant");
            m.put(SHOP + "identity.grant-secret-ref", "env:" + AppFixtures.HOOK_SECRET_ENV);
        });

        runner.withPropertyValues(asProperties(p)).run(context -> {
            assertThat(context).hasNotFailed();
            AppConfig app = context.getBean(AppRegistry.class).byId("shop").orElseThrow();
            assertThat(app.identity().type()).isEqualTo(ReliablePushProperties.IdentityType.SIGNED_GRANT);
            assertThat(app.identity().grantSecret().value()).isEqualTo("generated-test-hook-secret");
        });
    }

    @Test
    void rejectsMalformedSenderKeyHash() {
        Map<String, Object> p = shop(m -> m.put(SHOP + "sender-api-key-sha256", "not-a-hash"));

        runner.withPropertyValues(asProperties(p)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("senderApiKeySha256");
        });
    }

    @Test
    void rejectsTwoAppsSharingOneSenderKey() {
        Map<String, Object> p = shop(m -> m.putAll(AppFixtures.minimalApp("bank", "k-123")));

        runner.withPropertyValues(asProperties(p)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("sender-api-key-sha256");
        });
    }

    @Test
    void rejectsUnknownPolicy() {
        Map<String, Object> p = shop(m -> m.put(SHOP + "default-policy", "SOMETIMES"));

        runner.withPropertyValues(asProperties(p)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("defaultPolicy");
        });
    }

    @Test
    void rejectsCurrentKidWithoutMatchingSigningKey() {
        runner.withPropertyValues(
                        "reliable-push.device-token.signing-keys[0].kid=k1",
                        "reliable-push.device-token.signing-keys[0].private-key-pem-ref=env:" + AppFixtures.HOOK_SECRET_ENV,
                        "reliable-push.device-token.signing-keys[0].public-key-pem-ref=env:" + AppFixtures.HOOK_SECRET_ENV,
                        "reliable-push.device-token.current-kid=k2")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure())).contains("current-kid");
                });
    }

    @Test
    void rejectsInlinePrivateKey() {
        runner.withPropertyValues(
                        "reliable-push.device-token.signing-keys[0].kid=k1",
                        "reliable-push.device-token.signing-keys[0].private-key-pem-ref=-----BEGIN PRIVATE KEY-----",
                        "reliable-push.device-token.signing-keys[0].public-key-pem-ref=env:" + AppFixtures.HOOK_SECRET_ENV,
                        "reliable-push.device-token.current-kid=k1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    String message = rootMessage(context.getStartupFailure());
                    assertThat(message).contains("private-key-pem-ref").doesNotContain("BEGIN PRIVATE KEY");
                });
    }

    @Test
    void rejectsInlineOrMissingPublicKey() {
        runner.withPropertyValues(
                        "reliable-push.device-token.signing-keys[0].kid=k1",
                        "reliable-push.device-token.signing-keys[0].private-key-pem-ref=env:" + AppFixtures.HOOK_SECRET_ENV,
                        "reliable-push.device-token.signing-keys[0].public-key-pem-ref=-----BEGIN PUBLIC KEY-----",
                        "reliable-push.device-token.current-kid=k1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    String message = rootMessage(context.getStartupFailure());
                    assertThat(message).contains("public-key-pem-ref").doesNotContain("BEGIN PUBLIC KEY");
                });
        runner.withPropertyValues(
                        "reliable-push.device-token.signing-keys[0].kid=k1",
                        "reliable-push.device-token.signing-keys[0].private-key-pem-ref=env:" + AppFixtures.HOOK_SECRET_ENV,
                        "reliable-push.device-token.current-kid=k1")
                .run(context -> assertThat(context).hasFailed());
    }

    private static String rootMessage(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n');
        }
        return all.toString();
    }
}
