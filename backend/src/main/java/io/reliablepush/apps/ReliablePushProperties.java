package io.reliablepush.apps;

import io.reliablepush.shared.config.SecretRefs;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.lang.Nullable;
import org.springframework.validation.annotation.Validated;

/**
 * Typed {@code reliable-push.*} configuration (spec 8). Declarative checks use Bean Validation; cross-field and
 * environment checks (trigger window vs backoff, secret refs, fast clock) run in {@link AppRegistry} and
 * {@code TimeScale} at startup and throw {@link IllegalStateException}. Secret/key values are never held here, only
 * {@code env:NAME} references.
 */
@Validated
@ConfigurationProperties("reliable-push")
public record ReliablePushProperties(
        @Valid @DefaultValue DeviceToken deviceToken,
        @Valid @DefaultValue Worker worker,
        @DefaultValue("30") @Min(1) int retentionDays,
        @DefaultValue Testing testing,
        Map<String, @Valid AppProperties> apps) {

    public ReliablePushProperties {
        apps = apps == null ? Map.of() : Map.copyOf(apps);
    }

    public record DeviceToken(
            @DefaultValue List<@Valid SigningKey> signingKeys,
            @Nullable String currentKid,
            @DefaultValue("60s") @NotNull Duration revocationCacheTtl) {

        public DeviceToken {
            signingKeys = signingKeys == null ? List.of() : List.copyOf(signingKeys);
            if (!signingKeys.isEmpty()) {
                Set<String> kids = new HashSet<>();
                for (SigningKey key : signingKeys) {
                    if (!kids.add(key.kid())) {
                        throw new IllegalStateException(
                                "reliable-push.device-token.signing-keys contains duplicate kid " + key.kid());
                    }
                    SecretRefs.requireEnvRef("reliable-push.device-token.signing-keys[" + key.kid() + "].private-key-pem-ref",
                            key.privateKeyPemRef());
                    SecretRefs.requireEnvRef("reliable-push.device-token.signing-keys[" + key.kid() + "].public-key-pem-ref",
                            key.publicKeyPemRef());
                }
                if (currentKid == null || !kids.contains(currentKid)) {
                    throw new IllegalStateException(
                            "reliable-push.device-token.current-kid must be the kid of one of the configured signing-keys");
                }
            }
        }
    }

    /** A P-256 key pair: {@code private-key-pem-ref} is PKCS#8, {@code public-key-pem-ref} is SPKI; both {@code env:NAME}. */
    public record SigningKey(@NotBlank String kid, @NotBlank String privateKeyPemRef, @NotBlank String publicKeyPemRef) {}

    public record Worker(
            @DefaultValue("5s") @NotNull Duration pollInterval,
            @DefaultValue("100") @Min(1) @Max(10_000) int batchSize,
            @DefaultValue("2m") @NotNull Duration lease) {}

    /** {@code minuteDuration} is null unless the fast clock is requested; allowed only in profiles e2e or demo. */
    public record Testing(@Nullable Duration minuteDuration) {}

    public enum IdentityType {
        OIDC,
        SIGNED_GRANT
    }

    public record AppProperties(
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}", message = "must be 64 lowercase hex characters (SHA-256)")
                    String senderApiKeySha256,
            @Valid @NotNull Identity identity,
            @DefaultValue("90d") @NotNull Duration deviceTokenValidity,
            @DefaultValue("NOT_DELIVERED") @Pattern(regexp = "NOT_DELIVERED|NOT_READ") String defaultPolicy,
            @DefaultValue("10") @Min(1) int defaultTimeoutMinutes,
            @Valid @DefaultValue RateLimits rateLimits,
            @Valid @NotNull Fallback fallback) {}

    public record Identity(
            @NotNull IdentityType type,
            @NotBlank String issuer,
            @NotBlank String audience,
            @DefaultValue("sub") @NotBlank String userClaim,
            @Nullable String jwksUri,
            @Nullable String grantSecretRef) {}

    public record RateLimits(
            @DefaultValue("60") @Min(1) int devicePerMinute, @DefaultValue("6000") @Min(1) int senderPerMinute) {}

    public record Fallback(
            @NotBlank String url,
            @NotBlank String hmacSecretRef,
            @DefaultValue("5") @Min(1) @Max(20) int maxAttempts,
            @DefaultValue({"1m", "2m", "4m", "8m"}) @NotNull List<@NotNull Duration> backoff,
            @DefaultValue("20") @Min(1) int triggerWindowMinutes,
            @DefaultValue("10s") @NotNull Duration httpTimeout) {}
}
