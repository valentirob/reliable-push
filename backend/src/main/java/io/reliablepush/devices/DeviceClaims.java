package io.reliablepush.devices;

import java.time.Instant;
import java.util.UUID;

/**
 * The verified content of a device token (spec 5): {@code aud}=appId, {@code sub}=userId, {@code did}=deviceId,
 * {@code ver}=token_version, {@code iat}, {@code exp}. Instants have second precision, as in the JWT.
 */
public record DeviceClaims(
        String appId, String userId, UUID deviceId, int tokenVersion, Instant issuedAt, Instant expiresAt) {}
